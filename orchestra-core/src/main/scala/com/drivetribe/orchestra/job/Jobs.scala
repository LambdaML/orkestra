package com.drivetribe.orchestra.job

import java.io.{IOException, PrintStream}
import java.time.Instant

import scala.concurrent.Future
import scala.util.DynamicVariable

import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.http.HttpClient
import io.circe.{Encoder, Json}
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.java8.time._

import com.drivetribe.orchestra.model.Indexed._
import com.drivetribe.orchestra.model.RunInfo
import com.drivetribe.orchestra.utils.AkkaImplicits._
import com.drivetribe.orchestra.utils.BaseEncoders._

private[orchestra] object Jobs {

  def pong(runInfo: RunInfo)(implicit elasticsearchClient: HttpClient) =
    elasticsearchClient
      .execute(
        updateById(HistoryIndex.index.name, HistoryIndex.`type`, HistoryIndex.formatId(runInfo))
          .doc(Json.obj("latestUpdateOn" -> Instant.now().asJson))
      )
      .map(_.fold(failure => throw new IOException(failure.error.reason), identity))

  def succeedJob[Result: Encoder](runInfo: RunInfo, result: Result)(implicit elasticsearchClient: HttpClient) =
    elasticsearchClient
      .execute(
        updateById(HistoryIndex.index.name, HistoryIndex.`type`, HistoryIndex.formatId(runInfo))
          .doc(Json.obj("result" -> Option(Right(result): Either[Throwable, Result]).asJson))
          .retryOnConflict(1)
      )
      .map(_.fold(failure => throw new IOException(failure.error.reason), identity))

  def failJob(runInfo: RunInfo, throwable: Throwable)(implicit elasticsearchClient: HttpClient) =
    elasticsearchClient
      .execute(
        updateById(HistoryIndex.index.name, HistoryIndex.`type`, HistoryIndex.formatId(runInfo))
          .doc(Json.obj("result" -> Option(Left(throwable): Either[Throwable, Unit]).asJson))
          .retryOnConflict(1)
      )
      .flatMap(_ => Future.failed(throwable))

  /** Sets the standard out and err across all thread.
    * This is not Thread safe!
    */
  def withOutErr[Result](stream: PrintStream)(f: => Result): Result = {
    val systemOut = System.out
    val systemErr = System.err
    val outVarField = Console.getClass.getDeclaredField("outVar")
    outVarField.setAccessible(true)
    val consoleOut = Console.out
    val errVarField = Console.getClass.getDeclaredField("errVar")
    errVarField.setAccessible(true)
    val consoleErr = Console.err

    try {
      System.setOut(stream)
      System.setErr(stream)
      outVarField.set(Console, new DynamicVariable(stream))
      errVarField.set(Console, new DynamicVariable(stream))
      Console.withOut(stream)(Console.withErr(stream)(f))
    } finally {
      stream.flush()
      stream.close()
      System.setOut(systemOut)
      System.setErr(systemErr)
      outVarField.set(Console, new DynamicVariable(consoleOut))
      errVarField.set(Console, new DynamicVariable(consoleErr))
    }
  }
}