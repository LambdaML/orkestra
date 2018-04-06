package com.drivetribe.orchestra

import org.scalatest.Matchers._
import shapeless.HNil

import com.drivetribe.orchestra.utils.DummyJobs._
import com.drivetribe.orchestra.model.RunId
import com.drivetribe.orchestra.utils._

class RunningJobsTests extends OrchestraSpec with OrchestraConfigTest with KubernetesTest with ElasticsearchTest {

  scenario("Trigger a job") {
    emptyJobRunner.ApiServer().trigger(RunId.random(), HNil).futureValue
    eventually {
      val runningJobs = CommonApiServer().runningJobs().futureValue
      runningJobs should have size 1
    }
  }

  scenario("No running job") {
    val runningJobs = CommonApiServer().runningJobs().futureValue
    runningJobs should have size 0
  }
}