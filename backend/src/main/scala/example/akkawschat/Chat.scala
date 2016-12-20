package example.akkawschat

import akka.actor._
import akka.NotUsed
import akka.stream.{ KillSwitches, Materializer, UniqueKillSwitch }
import akka.stream.scaladsl.{ Keep, MergeHub }
import scala.concurrent.duration._
import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import shared.Protocol

trait Chat {
  def chatFlow(sender: String): Flow[String, Protocol.Message, Any]

  def injectMessage(message: Protocol.ChatMessage): Unit
}

object Chat {
  def create(implicit fm: Materializer, system: ActorSystem): Chat = {

    // from http://doc.akka.io/docs/akka/2.4.14/scala/stream/stream-dynamic.html#Combining_dynamic_stages_to_build_a_simple_Publish-Subscribe_service

    // Obtain a Sink and Source which will publish and receive from the "bus" respectively.
    val (sink, source) =
      MergeHub.source[String](perProducerBufferSize = 16)
        .toMat(BroadcastHub.sink(bufferSize = 256))(Keep.both)
        .run()
    // Ensure that the Broadcast output is dropped if there are no listening parties.
    // If this dropping Sink is not attached, then the broadcast hub will not drop any
    // elements itself when there are no subscribers, backpressuring the producer instead.
    source.runWith(Sink.ignore)
    // We create now a Flow that represents a publish-subscribe channel using the above
    // started stream as its "topic". We add two more features, external cancellation of
    // the registration and automatic cleanup for very slow subscribers.
    val busFlow: Flow[String, String, UniqueKillSwitch] =
      Flow.fromSinkAndSource(sink, source)
        .joinMat(KillSwitches.singleBidi[String, String])(Keep.right)
        .backpressureTimeout(3.seconds)

    // actor where messages may be sent to which are broadcasted
    val broadcaster = sink.runWith(Source.actorRef[String](1, OverflowStrategy.fail))

    val chatActor =
      system.actorOf(Props(new Actor {
        var subscribers = Set.empty[String]

        def receive: Receive = {
          case NewParticipant(name) ⇒
            subscribers += name
            broadcast(Protocol.Joined(name, members))
          case msg: ReceivedMessage ⇒
            println(s"Recieved Message (will not broadcast) ${msg}")
          case msg: Protocol.ChatMessage ⇒
            println(s"Recieved Message ${msg}")
            broadcast(msg)
          case ParticipantLeft(person) ⇒
          /*
            val entry @ (name, ref) = subscribers.find(_._1 == person).get
            // report downstream of completion, otherwise, there's a risk of leaking the
            // downstream when the TCP connection is only half-closed
            ref ! Status.Success(Unit)
            subscribers -= entry
            dispatch(Protocol.Left(person, members))
             */
          case Terminated(sub)         ⇒
          // clean up dead subscribers, but should have been removed when `ParticipantLeft`
          //subscribers = subscribers.filterNot(_._2 == sub)
          case s                       ⇒ println(s"Received something: ${s}")
        }
        def sendAdminMessage(msg: String): Unit = broadcast(Protocol.ChatMessage("admin", msg))
        def broadcast(msg: Protocol.Message): Unit = broadcaster ! msg.toString
        def members = subscribers.toSeq
      }))

    // Wraps the chatActor in a sink. When the stream to this sink will be completed
    // it sends the `ParticipantLeft` message to the chatActor.
    // FIXME: here some rate-limiting should be applied to prevent single users flooding the chat
    //    def chatInSink(sender: String) = Sink.actorRef[ChatEvent](chatActor, ParticipantLeft(sender))
    source.runForeach(msg ⇒ chatActor ! msg)

    new Chat {
      def chatFlow(sender: String): Flow[String, Protocol.ChatMessage, Any] = {

        chatActor ! NewParticipant(sender)
        val chatMsgParser = """ChatMessage\(([^,]+),([^\)]+)\)""".r
        val out: Source[Protocol.ChatMessage, NotUsed] =
          source.map { s: String ⇒
            // TODO handle Protocol.Joined messages as they are broadcasted when a new client joins
            chatMsgParser.findFirstIn(s) match {
              case Some(chatMsgParser(sender, message)) ⇒ Protocol.ChatMessage(sender, message)
              case None                                 ⇒ Protocol.ChatMessage("server", s"received unparsable message: ${s}")
            }
          }

        // The counter-part which is a source that will create a target ActorRef per
        // materialization where the chatActor will send its messages to.
        // This source will only buffer one element and will fail if the client doesn't read
        // messages fast enough.
        /*
           */
        val in: Sink[String, NotUsed] = sink.contramap { s ⇒ Protocol.ChatMessage(sender, s).toString }

        Flow.fromSinkAndSource(in, out)
      }
      def injectMessage(message: Protocol.ChatMessage): Unit = chatActor ! message // non-streams interface
    }
  }

  private sealed trait ChatEvent
  private case class NewParticipant(name: String) extends ChatEvent
  private case class ParticipantLeft(name: String) extends ChatEvent
  private case class ReceivedMessage(sender: String, message: String) extends ChatEvent {
    def toChatMessage: Protocol.ChatMessage = Protocol.ChatMessage(sender, message)
  }
}
