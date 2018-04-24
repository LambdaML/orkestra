package com.drivetribe.orchestra.integration.tests.utils

import com.drivetribe.orchestra.board.JobBoard
import shapeless.HList
import com.drivetribe.orchestra.{CommonApi, OrchestraConfig}

object Api {
  def jobClient[ParamValues <: HList, Result](job: JobBoard[ParamValues, Result, _, _]) =
    AutowireClient(Kubernetes.client, s"${OrchestraConfig.jobSegment}/${job.id.value}")[job.Api]

  val commonClient = AutowireClient(Kubernetes.client, OrchestraConfig.commonSegment)[CommonApi]
}
