package dit4c.gatehouse.docker

import scala.concurrent.duration._
import akka.actor.Actor
import scala.collection.immutable.Queue
import akka.actor.ActorRef
import akka.event.LoggingReceive
import akka.event.Logging
import scala.util.{Success,Failure}

class DockerIndexActor(dockerClient: DockerClient) extends Actor {
  import context.dispatcher
  val log = Logging(context.system, this)
  val tick =
    context.system.scheduler.schedule(1000 millis, 1000 millis, self, "tick")

  import DockerIndexActor._

  private case class DelayedQuery(sender: ActorRef, query: PortQuery)
  private case class UpdatePortIndex(index: Map[String, String])

  private var queue: Queue[DelayedQuery] = Queue.empty

  override def preStart = pollDocker

  // Common Receive logic
  private val commonReceive: Receive = {
    case UpdatePortIndex(newIndex) =>
      context.become(respondWith(newIndex, false))
      log.info(s"Using new index: $newIndex")
  }

  // Enqueue until we've got some data
  val receive: Receive = commonReceive orElse {
    case "tick" =>
      pollDocker
    case query: PortQuery =>
      queue = queue enqueue DelayedQuery(sender, query)
  }

  // Respond using index
  def respondWith(index: Map[String, String], waiting: Boolean): Receive = {
    clearQueue
    commonReceive orElse {
      case "tick" =>
        if (!waiting) {
          pollDocker
          context.become(respondWith(index, true))
        } else log.info("waiting on Docker poll")
      case DelayedQuery(originalSender, PortQuery(containerName)) =>
        originalSender ! PortReply(index.get(containerName))
      case PortQuery(containerName) =>
        sender ! PortReply(index.get(containerName))
    }
  }

  private def clearQueue = {
    queue.foreach(self ! _)
    queue = Queue.empty
  }

  private def pollDocker = {
    dockerClient.containerPorts.onComplete({
      case Success(m: Map[String, String]) =>
        self ! UpdatePortIndex(m)
      case Failure(e) =>
        log.warning(s"Docker poll failed: $e\n${e.getStackTrace.toSeq}")
    })
  }

}

object DockerIndexActor {
  case class PortQuery(containerName: String)
  case class PortReply(port: Option[String])
}
