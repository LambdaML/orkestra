package com.goyeau.orchestra

import com.goyeau.orchestra.board.Board
import com.goyeau.orchestra.css.AppCss
import com.goyeau.orchestra.route.WebRouter
import com.goyeau.kubernetesclient.KubernetesClient
import com.sksamuel.elastic4s.http.HttpClient
import org.scalajs.dom

/**
  * Mix in this trait to create the Orchestra server.
  */
trait Orchestra extends OrchestraPlugin {
  implicit override def orchestraConfig: OrchestraConfig = ???
  implicit override def kubernetesClient: KubernetesClient = ???
  implicit override def elasticsearchClient: HttpClient = ???

  def board: Board

  def main(args: Array[String]): Unit = {
    AppCss.load()
    WebRouter.router(board).renderIntoDOM(dom.document.getElementById(BuildInfo.projectName.toLowerCase))
  }
}