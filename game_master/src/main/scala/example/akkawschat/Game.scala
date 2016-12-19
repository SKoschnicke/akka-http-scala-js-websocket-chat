package example.akkawschat

import akka.actor.ActorSystem
import akka.stream.ThrottleMode

import scala.util.control.NonFatal

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Source, Flow }

import akka.http.scaladsl.model.Uri
import akka.stream.stage.{ TerminationDirective, SyncDirective, Context, PushStage }

import akka.stream.Materializer

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.ActorSystem

import akka.stream.scaladsl.{ Keep, Source, Sink, Flow }

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.ws._

import upickle.default._

import shared.Protocol

// FIXME: this is copied from the cli project
object ChatClient {
  def connect[T](endpoint: Uri, handler: Flow[Protocol.Message, String, T])(implicit system: ActorSystem, materializer: Materializer): Future[T] = {
    val wsFlow: Flow[Message, Message, T] =
      Flow[Message]
        .collect {
          case TextMessage.Strict(msg) ⇒ read[Protocol.Message](msg)
        }
        .viaMat(handler)(Keep.right)
        .map(TextMessage(_))

    val (fut, t) = Http().singleWebSocketRequest(WebSocketRequest(endpoint), wsFlow)
    fut.map {
      case v: ValidUpgrade                         ⇒ t
      case InvalidUpgradeResponse(response, cause) ⇒ throw new RuntimeException(s"Connection to chat at $endpoint failed with $cause")
    }(system.dispatcher)
  }

  def connect[T](endpoint: Uri, in: Sink[Protocol.Message, Any], out: Source[String, Any])(implicit system: ActorSystem, materializer: Materializer): Future[Unit] =
    connect(endpoint, Flow.fromSinkAndSource(in, out)).map(_ ⇒ ())(system.dispatcher)

  def connect[T](endpoint: Uri, onMessage: Protocol.Message ⇒ Unit, out: Source[String, Any])(implicit system: ActorSystem, materializer: Materializer): Future[Unit] =
    connect(endpoint, Sink.foreach(onMessage), out)
}
// FIXME end =====================================

object GameMasterApp extends App {
  val endpointBase = "ws://localhost:8080/chat"
  val endpoint = Uri(endpointBase).withQuery(Uri.Query("name" -> "gameMaster"))

  implicit val system = ActorSystem()
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  import Console._
  def formatCurrentMembers(members: Seq[String]): String =
    s"(${members.size} people chatting: ${members.map(m ⇒ s"$YELLOW$m$RESET").mkString(", ")})"

  object GameMaster {
    type State = Seq[String] // current chat members
    def initialState: Seq[String] = Nil

    def run(): Unit = {
      val onMessage: Protocol.Message ⇒ Future[String] = { msg ⇒
        msg match {
          case Protocol.ChatMessage(sender, message) ⇒ print(s"$YELLOW$sender$RESET: $message")
          case Protocol.Joined(member, all)          ⇒ print(s"$YELLOW$member$RESET ${GREEN}joined!$RESET ${formatCurrentMembers(all)}")
          case Protocol.Left(member, all)            ⇒ print(s"$YELLOW$member$RESET ${RED}left!$RESET ${formatCurrentMembers(all)}")
          case _                                     ⇒ print("got something!")
        }
        Future.successful("hu something")
      }

      /*
      val inputFlow =
        Flow[Protocol.Message]
          .map(onMessage).map { _ ⇒ "" }

       */
      // this was initially to connect input and output flow
      val appFlow: Flow[Protocol.Message, String, NotUsed] =
        Flow[Protocol.Message].mapAsync(1)(onMessage)

      //val source: Source[String, NotUsed] = Source(1 to 100).throttle(1, 1.second, 1, ThrottleMode.shaping).map { i ⇒ s"Number: ${i}" }
      val source: Source[String, _] = Source.tick(1.second, 1.second, "Message from Game Master")

      println("Connecting... (Use Ctrl-D to exit.)")
      val f = ChatClient.connect(endpoint, appFlow)
      f.onFailure {
        case NonFatal(e) ⇒
          println(s"Connection to $endpoint failed because of '${e.getMessage}'")
          system.shutdown()
      }
      f.onSuccess {
        println(s"Connection to $endpoint succeeded.")
        //system.shutdown()
        PartialFunction.empty
      }
    }
  }
  println("Game Master rising")
  GameMaster.run()
}
