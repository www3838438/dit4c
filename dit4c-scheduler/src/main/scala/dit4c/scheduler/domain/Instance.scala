package dit4c.scheduler.domain

import scala.util.Random
import akka.persistence.fsm.PersistentFSM
import scala.reflect._
import scala.concurrent.duration._
import java.time.Instant
import java.security.interfaces.{RSAPublicKey => JavaRSAPublicKey}
import akka.actor.Props
import akka.actor.ActorRef

object Instance {

  type Id = String
  type ImageName = String
  type ImageId = String

  def props(worker: ActorRef): Props =
    Props(classOf[Instance], worker)

  sealed trait SourceImage
  case class NamedImage(name: ImageName) extends SourceImage
  case class LocalImage(id: ImageId) extends SourceImage

  sealed trait InstanceSigningKey
  case class RSAPublicKey(key: JavaRSAPublicKey) extends InstanceSigningKey

  /**
   * 128-bit identifier as hexadecimal
   */
  def newId = Seq.fill(16)(Random.nextInt(255)).map(i => f"$i%x").mkString

  trait State extends BasePersistentFSMState
  case object JustCreated extends State
  case object WaitingForImage extends State
  case object Starting extends State
  case object Running extends State
  case object Stopping extends State
  case object Finished extends State
  case object Errored extends State

  sealed trait Data
  case object NoData extends Data
  case class StartData(
      instanceId: String,
      providedImage: SourceImage,
      resolvedImage: Option[LocalImage],
      callbackUrl: String) extends Data

  trait Command
  case object GetStatus extends Command
  case class Initiate(
      instanceId: String,
      image: SourceImage,
      callbackUrl: String) extends Command
  case class ReceiveImage(id: LocalImage) extends Command
  case class AssociateSigningKey(key: InstanceSigningKey) extends Command
  case object ConfirmStart extends Command
  case object Terminate extends Command
  case object ConfirmTerminated extends Command
  case class Error(msg: String) extends Command

  trait DomainEvent extends BaseDomainEvent
  case class Initiated(
      val instanceId: String,
      val image: SourceImage,
      val callbackUrl: String,
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class FetchedImage(
      val image: LocalImage,
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class AssociatedSigningKey(
      val key: InstanceSigningKey,
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class ConfirmedStart(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class RequestedTermination(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class Terminated(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class ErrorOccurred(
      val message: String,
      val timestamp: Instant = Instant.now) extends DomainEvent

}

class Instance(worker: ActorRef)
    extends PersistentFSM[Instance.State, Instance.Data, Instance.DomainEvent] {
  import Instance._

  lazy val persistenceId = self.path.name

  startWith(JustCreated, NoData)

  when(JustCreated) {
    case Event(Initiate(id, image @ NamedImage(imageName), callback), _) =>
      val domainEvent = Initiated(id, image, callback)
      goto(WaitingForImage).applying(domainEvent).andThen {
        case data =>
          log.info(s"Fetching image: $image")
          worker ! InstanceWorker.Fetch(image)
      }
  }

  when(WaitingForImage) {
    case Event(ReceiveImage(localImage), StartData(id, _, _, callback)) =>
      goto(Starting).applying(FetchedImage(localImage)).andThen {
        case data =>
          log.info(s"Starting with: $localImage")
          worker ! InstanceWorker.Start(id, localImage, callback)
      }
  }

  when(Starting) {
    case Event(AssociateSigningKey(key), _) =>
      log.info(s"Received signing key: $key")
      stay.applying(AssociatedSigningKey(key))
    case Event(ConfirmStart, _) =>
      log.info(s"Confirmed start")
      goto(Starting).applying(ConfirmedStart())
  }

  when(Running) {
    case Event(Terminate, StartData(id, _, _, _) ) =>
      // Ask cluster to stop instance
      worker ! InstanceWorker.Terminate(id)
      goto(Stopping).applying(RequestedTermination())
  }

  when(Stopping) {
    case Event(ConfirmTerminated, _) =>
      goto(Finished).applying(Terminated())
  }

  when(Finished, stateTimeout = 1.minute) {
    case Event(StateTimeout, _) =>
      stop // Done lingering for new commands
  }

  when(Errored, stateTimeout = 1.minute) {
    case Event(StateTimeout, _) =>
      stop // Done lingering for new commands
  }

  whenUnhandled {
    case Event(GetStatus, data) =>
      stay replying data
    case Event(Error(msg), _) =>
      goto(Errored).applying(ErrorOccurred(msg))
  }

  def applyEvent(
      domainEvent: DomainEvent,
      currentData: Data): Instance.Data = {
    (domainEvent, currentData) match {
      case (Initiated(id, image, callback, _), NoData) =>
        StartData(id, image, None, callback)
      case (FetchedImage(image, _), data: StartData) =>
        data.copy(resolvedImage = Some(image))
      case (e, d) =>
        stop(PersistentFSM.Failure(s"Unhandled event/state: ($e, $d)"))
        d
    }
  }

  def domainEventClassTag: ClassTag[Instance.DomainEvent] =
    classTag[DomainEvent]


}