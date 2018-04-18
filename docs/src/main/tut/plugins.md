---
layout: docs
title:  "Plugins"
position: 9
---

# Plugins

Documentation coming soon

Orchestra doesn't have a plugins system like you can find in other CD tools with their own store of plugins.
Instead it relies on the jar dependency and distribution system like Maven or Ivy 
There is multiple advantages to this system:
- Since Scala is a JVM language we have access to all the libraries from the JVM community (Scala, Java or any other
JVM compiler languages).
- Installation of plugins becomes code too so it's very easy to code review and rollback changes

## Talking to AWS
In order to talk to AWS we can use the [official Java SDK](https://github.com/aws/aws-sdk-java). Lets add the dependency
to `build.sbt`:
```scala
libraryDependencies += "com.amazonaws" % "aws-java-sdk" % "AWS SDK Version"
```

Now we can define a function `uploadToS3()` that we will be able to use in any job:
```tut:silent
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.transfer.TransferManagerBuilder
import com.drivetribe.orchestra.filesystem._
import com.drivetribe.orchestra.utils.BlockingShells._

// We need the implicit workDir in order to know in which directory we are working in
def uploadToS3()(implicit workDir: Directory) = {
  val s3 = AmazonS3ClientBuilder.standard.withRegion(Regions.EU_WEST_1).build()
  val transferManager = TransferManagerBuilder.standard.withS3Client(s3).build()

  // Creating the file locally 
  val file = LocalFile("uploadme.txt")
  sh(s"echo 'Hey!' > ${file.getName}")

  val s3Bucket = "some-bucket-name"
  println(s"Uploading ${file.getName} to S3 bucket s3Bucket")
  transferManager.upload(s3Bucket, file.getName, file).waitForCompletion()
}
```

## Sending a Slack message
Let's try to integrate with Slack. If I Google "slack scala" and hit "I'm feeling lucky" I end up on this Slack client
[https://github.com/gilbertw1/slack-scala-client](https://github.com/gilbertw1/slack-scala-client). So it seems that
someone already wrote a Slack plugin for Orchestra even before Orchestra was born!  
Lets add the dependency to `build.sbt`:
```scala
libraryDependencies += "com.github.gilbertw1" %% "slack-scala-client" % "Slack client version"
```

And create the `sendSlackMessage()` function:
```tut:silent
import slack.api.SlackApiClient
// Orchestra already uses Akka so we can import the implicits for the Slack too
import com.drivetribe.orchestra.utils.AkkaImplicits._

def sendSlackMessage() = {
  val slack = SlackApiClient("slack token")
  slack.postChatMessage("channel name", "Hello!")
}
```
You will be able to get the token by creating a Slack app on https://api.slack.com/apps and then adding it to your
workspace.