package io.chumps.orchestra

import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.HttpClient
import cats.implicits._

import io.chumps.orchestra.model.Indexed
import io.chumps.orchestra.utils.AkkaImplicits._

object Elasticsearch {

  lazy val client = HttpClient(OrchestraConfig.elasticsearchUri)

  def init() = Indexed.indices.toList.traverse(index => client.execute(index.createDefinition))
}