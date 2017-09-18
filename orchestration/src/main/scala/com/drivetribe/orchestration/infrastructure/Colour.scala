package com.drivetribe.orchestration.infrastructure

import scala.collection.convert.ImplicitConversions._

import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.ec2.model.{DescribeTagsRequest, Filter}
import com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancingClientBuilder
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest
import com.drivetribe.orchestration.{Environment, EnvironmentColour}

object Colour {

  lazy val elb = AmazonElasticLoadBalancingClientBuilder.standard().withRegion(Regions.EU_WEST_1).build

  def getActive(environment: Environment): EnvironmentColour = {
    require(environment.isBiColour, s"$environment is not a bicolour environment")

    val tfState = TerraformState.fromS3(environment)
    val activeLoadBalancer = tfState.getResourceAttribute(Seq("root"), "aws_alb_target_group.active", "arn")

    val instanceIds = elb
      .describeTargetHealth(new DescribeTargetHealthRequest().withTargetGroupArn(activeLoadBalancer))
      .getTargetHealthDescriptions
      .map(_.getTarget.getId)

    val ec2 = AmazonEC2ClientBuilder.standard().withRegion(Regions.EU_WEST_1).build
    val instances = ec2.describeTags(
      new DescribeTagsRequest(Seq(new Filter("key", Seq("Colour")), new Filter("resource-id", instanceIds)))
    )
    val instanceColours = instances.getTags.map(_.getValue)
    val coloursAttached = instanceColours.distinct.size
    assert(coloursAttached == 1, s"Number of colour attached was not 1 but $coloursAttached")

    EnvironmentColour.withNameInsensitive(instanceColours.head)
  }

  def isHealthy(targetGroupArn: String) =
    elb
      .describeTargetHealth(new DescribeTargetHealthRequest().withTargetGroupArn(targetGroupArn))
      .getTargetHealthDescriptions
      .map(_.getTargetHealth.getState)
      .forall {
        case "healthy"   => true
        case "unhealthy" => throw new IllegalStateException("Switching process aborted due to health check failing")
        case _           => false
      }
}