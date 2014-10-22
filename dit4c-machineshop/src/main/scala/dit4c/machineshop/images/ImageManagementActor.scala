package dit4c.machineshop.images

import dit4c.machineshop.{Image, ImageMetadata}
import dit4c.machineshop.docker.DockerClient
import dit4c.machineshop.docker.models.DockerImage
import akka.actor.{Actor, ActorRef, Cancellable, Props, Stash}
import akka.event.Logging
import java.util.Calendar
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, FiniteDuration}

class ImageManagementActor(
      knownImages: KnownImages,
      dockerClient: DockerClient,
      imagePullInterval: Option[FiniteDuration])
    extends Actor with Stash {
  val log = Logging(context.system, this)

  implicit val executionContext = context.system.dispatcher
  implicit val actorRefFactory = context.system

  import ImageManagementActor._

  object FetchImages
  case class FetchedImages(images: Seq[Image])

  val imagePollInterval = FiniteDuration(30, "second")

  // Poll images regularly to allow for external changes to the image list
  val fetchImagesScheduler: Cancellable =
    context.system.scheduler.schedule(
      imagePollInterval, imagePollInterval, self, FetchImages)

  val pullScheduler: Option[Cancellable] = imagePullInterval.map { interval =>
    val updateActor = context.actorOf(Props(classOf[ImageUpdateActor], self))
    context.system.scheduler.schedule(
      Duration.Zero, interval, updateActor, "tick")
  }

  override def preStart() {
    refetchImages // Trigger initial population
  }

  override def postStop() {
    pullScheduler.foreach(_.cancel)
  }

  lazy val receive: Receive =
    fetchImagesHandler orElse
    fetchedImagesHandler(None) orElse
    { case _ => stash() }

  def usingImages(images: Seq[Image]): Receive = {
    // Initialization
    log.info(s"Currently managing ${images.size} images.")
    // Receive handler
    fetchImagesHandler orElse
    fetchedImagesHandler(Some(images)) orElse
    {
      case AddImage(displayName, repository, tag) =>
        val conflictingImages = knownImages.find(_.displayName == displayName) ++
                                knownImages.find(_.ref == (repository, tag))
        conflictingImages match {
          case Nil =>
            val newKnownImage = KnownImage(displayName, repository, tag)
            knownImages += newKnownImage
            dockerClient.images.pull(repository, tag)
            sender ! AddingImage(new ImageImpl(newKnownImage))
            refetchImages
          case images =>
            sender ! ConflictingImages(images.map(new ImageImpl(_)).toSeq)
        }

      case PullImage(id) =>
        knownImages.find(i => i.id == id) match {
          case Some(knownImage: KnownImage) =>
            dockerClient.images.pull(knownImage.repository, knownImage.tag)
            sender ! PullingImage(new ImageImpl(knownImage))
            refetchImages
          case None =>
            sender ! UnknownImage(id)
        }

      case ListImages() => {
        sender ! ImageList(images)
      }

      case RemoveImage(id) =>
        knownImages.find(i => i.id == id) match {
          case Some(knownImage: KnownImage) =>
            knownImages -= knownImage
            sender ! RemovingImage(new ImageImpl(knownImage))
            refetchImages
          case None =>
            sender ! UnknownImage(id)
        }
    }
  }

  // Typically triggered when we know the existing image list is incorrect
  protected def refetchImages {
    fetchImages.onSuccess {
      case images => self ! FetchedImages(images)
    }
  }

  protected val fetchImagesHandler: Receive = {
    case FetchImages =>
      fetchImages.onSuccess {
        case images => self ! FetchedImages(images)
      }
  }

  protected def fetchedImagesHandler(existing: Option[Seq[Image]]): Receive = {
    case FetchedImages(images) =>
      unstashAll()
      existing match {
        case Some(currentImages) if currentImages == images =>
          // same images, so keeping same behaviour
        case _ =>
          context.become(usingImages(images))
      }
  }

  protected def fetchImages(): Future[Seq[Image]] = {
    val ki = knownImages.toSeq
    for (
      dockerImages <- dockerClient.images.list
    ) yield {
      def imageMetadata(i: KnownImage): Option[ImageMetadata] =
        dockerImages.find {
          _.names.exists(_ == i.repository+":"+i.tag)
        }.map(new ImageMetadataImpl(_))
      def toImage(ki: KnownImage) =
        new ImageImpl(ki, imageMetadata(ki))
      ki.map(toImage)
    }
  }

}

object ImageManagementActor {

  class ImageUpdateActor(manager: ActorRef) extends Actor {
    val log = Logging(context.system, this)

    override def receive = {
      case "tick" =>
        manager ! ListImages()
      case ImageList(images) => images.foreach { image =>
        manager ! PullImage(image.id)
      }
      case PullingImage(image) =>
        log.info(s"Pulling image: $image")
    }
  }


  case class ImageImpl(
      val id: String,
      val displayName: String,
      val repository: String,
      val tag: String,
      val metadata: Option[ImageMetadata]) extends Image {

    def this(ki: KnownImage, metadata: Option[ImageMetadata] = None) =
      this(ki.id, ki.displayName, ki.repository, ki.tag, metadata)

  }

  case class ImageMetadataImpl(
      val id: String,
      val created: Calendar) extends ImageMetadata {

    def this(di: DockerImage) = this(di.id, di.created)

  }

  case class AddImage(displayName: String, repository: String, tag: String)
  case class PullImage(id: String)
  case class ListImages()
  case class RemoveImage(id: String)

  case class AddingImage(image: Image)
  case class ConflictingImages(images: Seq[Image])
  case class PullingImage(image: Image)
  case class RemovingImage(image: Image)
  case class ImageList(images: Seq[Image])
  case class UnknownImage(id: String)

}