package com.drivetribe.orchestra.board

import java.time.Instant

import scala.concurrent.{ExecutionContext, Future}

import io.circe.{Decoder, Encoder}
import io.circe.generic.auto._
import io.circe.java8.time._
import io.k8s.api.core.v1.PodSpec

import shapeless.ops.function.FnToProduct
import shapeless.{::, _}

import com.drivetribe.orchestra.job.SimpleJob
import com.drivetribe.orchestra.model._
import com.drivetribe.orchestra.parameter.{Parameter, ParameterOperations}
import com.drivetribe.orchestra.utils.{AutowireClient, AutowireServer, RunIdOperation}
import com.drivetribe.orchestra.OrchestraConfig

trait Job[ParamValues <: HList, Result, Func, PodSpecFunc] extends Board {
  val id: JobId
  val segment = id.value
  val name: String

  private[orchestra] trait Api {
    def trigger(runId: RunId,
                params: ParamValues,
                tags: Seq[String] = Seq.empty,
                by: Option[RunInfo] = None): Future[Unit]
    def stop(runId: RunId): Future[Unit]
    def tags(): Future[Seq[String]]
    def history(page: Page[Instant]): Future[History[ParamValues, Result]]
  }

  private[orchestra] object Api {
    def router(apiServer: Api)(implicit ec: ExecutionContext,
                               encoderP: Encoder[ParamValues],
                               decoderP: Decoder[ParamValues],
                               encoderR: Encoder[Result],
                               decoderR: Decoder[Result]) =
      AutowireServer.route[Api](apiServer)

    val client = AutowireClient(s"${OrchestraConfig.jobSegment}/${id.value}")[Api]
  }
}

object Job {

  def apply[Func](id: JobId, name: String) = new JobBuilder[Func](id, name)

  class JobBuilder[Func](id: JobId, name: String) {
    // No Params
    def apply[ParamValuesNoRunId <: HList, ParamValues <: HList, Result, PodSpecFunc]()(
      implicit fnToProdFunc: FnToProduct.Aux[Func, ParamValues => Result],
      fnToProdPodSpec: FnToProduct.Aux[PodSpecFunc, ParamValues => PodSpec],
      paramOperations: ParameterOperations[HNil, ParamValuesNoRunId],
      runIdOperation: RunIdOperation[ParamValuesNoRunId, ParamValues],
      encoderP: Encoder[ParamValues],
      decoderP: Decoder[ParamValues],
      decoderR: Decoder[Result]
    ) = SimpleJob[ParamValuesNoRunId, ParamValues, HNil, Result, Func, PodSpecFunc](id, name, HNil)

    // One param
    def apply[ParamValuesNoRunId <: HList, ParamValues <: HList, Param <: Parameter[_], Result, PodSpecFunc](
      param: Param
    )(
      implicit fnToProdFunc: FnToProduct.Aux[Func, ParamValues => Result],
      fnToProdPodSpec: FnToProduct.Aux[PodSpecFunc, ParamValues => PodSpec],
      runIdOperation: RunIdOperation[ParamValuesNoRunId, ParamValues],
      paramOperations: ParameterOperations[Param :: HNil, ParamValuesNoRunId],
      encoderP: Encoder[ParamValues],
      decoderP: Decoder[ParamValues],
      decoderR: Decoder[Result]
    ) = SimpleJob[ParamValuesNoRunId, ParamValues, Param :: HNil, Result, Func, PodSpecFunc](id, name, param :: HNil)

    // Multi params
    def apply[ParamValuesNoRunId <: HList, ParamValues <: HList, TupledParams, Params <: HList, Result, PodSpecFunc](
      params: TupledParams
    )(
      implicit fnToProdFunc: FnToProduct.Aux[Func, ParamValues => Result],
      fnToProdPodSpec: FnToProduct.Aux[PodSpecFunc, ParamValues => PodSpec],
      tupleToHList: Generic.Aux[TupledParams, Params],
      runIdOperation: RunIdOperation[ParamValuesNoRunId, ParamValues],
      paramOperations: ParameterOperations[Params, ParamValuesNoRunId],
      encoderP: Encoder[ParamValues],
      decoderP: Decoder[ParamValues],
      decoderR: Decoder[Result]
    ) =
      SimpleJob[ParamValuesNoRunId, ParamValues, Params, Result, Func, PodSpecFunc](id, name, tupleToHList.to(params))
  }
}