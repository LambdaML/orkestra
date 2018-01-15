package io.chumps.orchestra.job

import java.io.IOException
import java.nio.file.Files
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, ZoneOffset}

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.io.Source

import akka.http.scaladsl.server.Directives.{entity, _}
import akka.http.scaladsl.server.Route
import autowire.Core
import io.circe.parser._
import io.circe.{Decoder, Encoder}
import io.k8s.api.core.v1.PodSpec
import shapeless._
import shapeless.ops.function.FnToProduct
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.http.HttpClient
import io.circe.generic.auto._
import io.circe.java8.time._

import io.chumps.orchestra.model.Indexed.StagesIndex
import io.chumps.orchestra.model.Indexed.Stage
import io.chumps.orchestra.ARunStatus._
import io.chumps.orchestra.board.Job
import io.chumps.orchestra.filesystem.Directory
import io.chumps.orchestra.kubernetes.JobUtils
import io.chumps.orchestra.model._
import io.chumps.orchestra.utils.Utils
import io.chumps.orchestra.utils.AkkaImplicits._
import io.chumps.orchestra.{ARunStatus, AutowireServer, Elasticsearch, OrchestraConfig}

case class JobRunner[ParamValues <: HList: Encoder: Decoder, Result: Encoder: Decoder](
  job: Job[ParamValues, Result, _, _],
  podSpec: ParamValues => PodSpec,
  func: ParamValues => Result
) {

  private[orchestra] def run(runInfo: RunInfo): Unit = {
    Utils.runInit(runInfo, Seq.empty)

    Utils.elasticsearchOutErr(runInfo.runId) {
      val running = system.scheduler.schedule(1.second, 1.second) {
        persist[Result](runInfo, Running(Instant.now()))
      }
      println(s"Running job ${job.name}")

      try {
        ARunStatus.current[Result](runInfo).collect {
          case Triggered(_, Some(by)) =>
            system.scheduler.schedule(1.second, 1.second) {
              ARunStatus.current[Result](by).collect {
                case Running(at) if at.isBefore(Instant.now().minus(1, ChronoUnit.MINUTES)) => JobUtils.delete(runInfo)
              }
            }
        }

        val paramFile = OrchestraConfig.paramsFile(runInfo).toFile
        val result = func(
          if (paramFile.exists()) decode[ParamValues](Source.fromFile(paramFile).mkString).fold(throw _, identity)
          else HNil.asInstanceOf[ParamValues]
        )

        println(s"Job ${job.name} completed")
        persist(runInfo, Success(Instant.now(), result))
      } catch {
        case t: Throwable =>
          failJob(runInfo, t)
      } finally {
        running.cancel()
        JobUtils.delete(runInfo)
      }
    }
  }

  private[orchestra] object ApiServer extends job.Api {
    override def trigger(runId: RunId,
                         values: ParamValues,
                         tags: Seq[String] = Seq.empty,
                         by: Option[RunInfo] = None): Unit = {
      val runInfo = RunInfo(job.id, runId)
      if (ARunStatus.current[Result](runInfo).isEmpty) {
        Utils.runInit(runInfo, tags)

        Utils.elasticsearchOutErr(runInfo.runId) {
          try {
            persist[Result](runInfo, Triggered(Instant.now(), by))
            Files.write(OrchestraConfig.paramsFile(runInfo), AutowireServer.write(values).getBytes)

            Await.result(JobUtils.create(runInfo, podSpec(values)), 1.minute)
          } catch {
            case t: Throwable =>
              failJob(runInfo, t)
              throw t
          }
        }
      }
    }

    override def stop(runId: RunId): Unit = JobUtils.delete(RunInfo(job.id, runId))

    override def tags(): Seq[String] = Seq(OrchestraConfig.tagsDir(job.id).toFile).filter(_.exists()).flatMap(_.list())

    override def history(
      page: Page[Instant]
    ): Seq[(RunId, Instant, ParamValues, Seq[String], ARunStatus[Result], Seq[Stage])] = {
      val from = page.after.fold(LocalDateTime.MAX)(LocalDateTime.ofInstant(_, ZoneOffset.UTC))

      val runs = for {
        runsByDate <- Stream(OrchestraConfig.runsByDateDir(job.id).toFile)
        if runsByDate.exists()
        yearDir <- runsByDate.listFiles().toStream.sortBy(-_.getName.toInt).dropWhile(_.getName.toInt > from.getYear)
        dayDir <- yearDir
          .listFiles()
          .toStream
          .sortBy(-_.getName.toInt)
          .dropWhile(dir => yearDir.getName.toInt == from.getYear && dir.getName.toInt > from.getDayOfYear)
        secondDir <- dayDir
          .listFiles()
          .toStream
          .sortBy(-_.getName.toInt)
          .dropWhile(_.getName.toInt > from.toEpochSecond(ZoneOffset.UTC))
        runId <- secondDir.list().toStream

        runInfo = RunInfo(job.id, RunId(runId))
        if OrchestraConfig.statusFile(runInfo).toFile.exists()
        startAt <- ARunStatus.history[Result](runInfo).headOption.map {
          case status: Triggered => status.at
          case status: Running   => status.at
          case status =>
            throw new IllegalStateException(s"$status is not of status type ${classOf[Triggered].getName}")
        }

        paramFile = OrchestraConfig.paramsFile(runInfo).toFile
        paramValues <- if (paramFile.exists()) decode[ParamValues](Source.fromFile(paramFile).mkString).toOption
        else Option(HNil.asInstanceOf[ParamValues])

        tags = for {
          tagsDir <- Seq(OrchestraConfig.tagsDir(job.id).toFile)
          if tagsDir.exists()
          tagDir <- tagsDir.listFiles()
          runId <- tagDir.list()
          if runId == runInfo.runId.value.toString
        } yield tagDir.getName

        currentStatus <- ARunStatus.current[Result](runInfo)

        stages = Await
          .result(
            {
              val a = HttpClient(OrchestraConfig.elasticsearchUri).execute(
                search(StagesIndex.index)
                  .query(termQuery("runId", runId))
                  .sortBy(fieldSort("startedOn").asc(), fieldSort("_id").desc())
              )
              a.failed.map(e => println("Eeeee: " + e.getMessage))
              a
            },
            1.minute
          )
          .fold(failure => throw new IOException(failure.error.reason), identity)
          .result
          .to[Stage]
      } yield (runInfo.runId, startAt, paramValues, tags, currentStatus, stages)

      runs.take(page.size)
    }
  }

  private def failJob(runInfo: RunInfo, t: Throwable) = {
    t.printStackTrace()
    persist[Result](runInfo, Failure(Instant.now(), t))
  }

  private[orchestra] val apiRoute: Route =
    path(job.id.name / Segments) { segments =>
      entity(as[String]) { entity =>
        val body = AutowireServer.read[Map[String, String]](entity)
        val request = job.Api.router(ApiServer).apply(Core.Request(segments, body))
        onSuccess(request)(complete(_))
      }
    }
}

object JobRunner {

  def apply[ParamValues <: HList, Result, Func, PodSpecFunc](job: Job[ParamValues, Result, Func, PodSpecFunc]) =
    new JobRunnerBuilder(job)

  class JobRunnerBuilder[ParamValues <: HList, Result, Func, PodSpecFunc](
    job: Job[ParamValues, Result, Func, PodSpecFunc]
  ) {
    def apply(func: Directory => Func)(implicit fnToProdFunc: FnToProduct.Aux[Func, ParamValues => Result],
                                       encoderP: Encoder[ParamValues],
                                       decoderP: Decoder[ParamValues],
                                       encoderR: Encoder[Result],
                                       decoderR: Decoder[Result]) =
      JobRunner(job, (_: ParamValues) => PodSpec(Seq.empty), fnToProdFunc(func(Directory("."))))

    def apply(podSpec: PodSpec)(func: Directory => Func)(
      implicit fnToProdFunc: FnToProduct.Aux[Func, ParamValues => Result],
      encoderP: Encoder[ParamValues],
      decoderP: Decoder[ParamValues],
      encoderR: Encoder[Result],
      decoderR: Decoder[Result]
    ) =
      JobRunner(job, (_: ParamValues) => podSpec, fnToProdFunc(func(Directory("."))))

    def apply(podSpecFunc: PodSpecFunc)(func: Directory => Func)(
      implicit fnToProdFunc: FnToProduct.Aux[Func, ParamValues => Result],
      fnToProdPodSpec: FnToProduct.Aux[PodSpecFunc, ParamValues => PodSpec],
      encoderP: Encoder[ParamValues],
      decoderP: Decoder[ParamValues],
      encoderR: Encoder[Result],
      decoderR: Decoder[Result]
    ) =
      JobRunner(job, fnToProdPodSpec(podSpecFunc), fnToProdFunc(func(Directory("."))))
  }
}
