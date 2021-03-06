package dit4c.scheduler.domain

import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.matcher.MatcherMacros
import akka.util.Timeout
import scala.concurrent.duration._
import akka.actor.ActorSystem
import org.scalacheck.Gen
import dit4c.scheduler.ScalaCheckHelpers
import akka.testkit.TestProbe
import org.specs2.matcher.Matcher
import scala.util.Random
import org.scalacheck.Arbitrary
import org.specs2.scalacheck.Parameters
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAPrivateKey
import scala.concurrent.Future
import java.security.KeyPairGenerator
import java.security.SecureRandom
import akka.actor.Props
import dit4c.scheduler.runner.RktRunner
import akka.actor.Terminated
import java.nio.file.Paths
import org.bouncycastle.openpgp.PGPPublicKeyRing
import dit4c.common.KeyHelpers.PGPKeyGenerators
import com.typesafe.config.ConfigFactory

class RktClusterManagerSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck with MatcherMacros {

  import ScalaCheckHelpers._
  import RktClusterManager._

  implicit val params = Parameters(minTestsOk = 20)
  implicit val arbSystem = Arbitrary(genSystem("ClusterAggregate"))
  val rktRunnerConfig =
    RktRunner.Config(
        Paths.get("/var/lib/dit4c-rkt"),
        "dit4c-instance-",
          "" /* Not used */,
          "" /* Not used */,
          None,
          "" /* Not used */)
  val configProvider = mockConfigProvider(rktRunnerConfig)

  "ClusterAggregate" >> {

    "GetRktNodeState" >> {

      "initially returns Uninitialized" >> {
        implicit val system =
          ActorSystem("RktClusterManager-GetRktNodeState-Uninitialized")
        prop({ (clusterId: String, rktNodeId: String) =>
          val probe = TestProbe()
          val manager =
            probe.childActorOf(
                RktClusterManager.props(clusterId, configProvider))
          probe.send(manager, GetRktNodeState(rktNodeId))
          probe.expectMsgType[RktNode.GetStateResponse](1.minute) must {
            be(RktNode.DoesNotExist)
          }
        }).setGens(Gen.identifier, Gen.listOfN(8, Gen.numChar).map(_.mkString)).noShrink
      }

    }

    "AddRktNode" >> {
      Seq("SHA-256", "MD5").foreach { alg =>
        s"initializes RktNode with config & $alg fingerprint" >> {
          import dit4c.common.KeyHelpers._
          val clusterId = "test-rkt"
          implicit val system = ActorSystem(s"RktClusterManager-AddRktNode-successful-$alg")
          val hostPublicKey = randomRSAPublicKey
          val probe = TestProbe()
          val manager =
              probe.childActorOf(
                  RktClusterManager.props(
                      clusterId,
                      mockRktRunnerFactory,
                      mockFetchSshHostKey(hostPublicKey)))
          probe.send(manager, AddRktNode(
              "", "169.254.42.34", 22, "testuser",
              Seq(hostPublicKey.ssh.fingerprint(alg)),
              "/var/lib/dit4c/rkt").fillId)
          val response = probe.expectMsgType[RktNodeAdded](1.minute)
          probe.send(manager, ClusterManager.GetStatus)
          val clusterState = probe.expectMsgType[CurrentClusterInfo](1.minute).clusterInfo
          ( clusterState.nodeIds must contain(response.nodeId) )
        }
      }

      "requires valid fingerprint" >> {
        import dit4c.common.KeyHelpers._
        val clusterId = "test-rkt"
        implicit val system = ActorSystem(
            "RktClusterManager-AddRktNode-failed",
            ConfigFactory.parseString("""
              akka.loglevel = "OFF"
              """))
        val hostPublicKey = randomRSAPublicKey
        val probe = TestProbe()
        val manager =
            probe.childActorOf(
                RktClusterManager.props(
                    clusterId,
                    mockRktRunnerFactory,
                    mockFetchSshHostKey(hostPublicKey)))
        probe.send(manager, AddRktNode(
            "", "169.254.42.34", 22, "testuser",
            Seq(randomRSAPublicKey.ssh.fingerprint("SHA-256")),
            "/var/lib/dit4c/rkt").fillId)
        probe.expectNoMsg(5.seconds)
        done
      }
    }

    "StartInstance" >> {
      "starts an instance" >> {
        import dit4c.common.KeyHelpers._
        val clusterId = "test-rkt"
        implicit val system =
          ActorSystem(s"RktClusterManager-StartInstance-start")
        val resolvedImageId = "sha512-"+Stream.fill(64)("0").mkString
        val resolvedPublicKey = randomPGPPublicKeyRing
        val runnerFactory =
          (_: RktNode.ServerConnectionDetails, _: String) =>
            new RktRunner {
              override def fetch(imageName: String): Future[String] =
                Future.successful(resolvedImageId)
              override def start(
                  instanceId: String,
                  image: String,
                  portalUri: String): Future[PGPPublicKeyRing] =
                Future.successful(resolvedPublicKey)
              override def stop(instanceId: String): Future[Unit] = ???
              override def export(instanceId: String) = ???
              override def uploadImage(instanceId: String,
                  imageServer: String,
                  portalUri: String): Future[Unit] = ???
              override def resolveStates(instanceIds: Set[String]) = ???
            }
        val probe = TestProbe()
        val hostPublicKey = randomRSAPublicKey
        val manager =
            probe.childActorOf(
                RktClusterManager.props(
                    clusterId,
                    runnerFactory,
                    mockFetchSshHostKey(hostPublicKey)))
        // Create some nodes
        val nodeIds = 1.to(3).map { i =>
          val probe = TestProbe()
          probe.send(manager, AddRktNode(
              "", s"169.254.42.$i", 22, "testuser",
              Seq(hostPublicKey.ssh.fingerprint("SHA-256")),
              "/var/lib/dit4c/rkt").fillId)
          val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded](1.minute)
          nodeId
        }
        // Schedule an instance
        val testImage = "docker://dit4c/gotty:latest"
        val testCallback = "http://example.test/"
        probe.send(manager, StartInstance(randomInstanceId, testImage, testCallback))
        val response = probe.expectMsgType[RktClusterManager.StartingInstance](1.minute)
        (response must {
          import scala.language.experimental.macros
          matchA[RktClusterManager.StartingInstance]
            .instanceId(not(beEmpty[String]))
        }) and
        {
          probe.send(manager, GetInstanceStatus(response.instanceId))
          val instanceStatus =
            probe.expectMsgType[Instance.StatusReport](1.minute)
          instanceStatus.data must beLike {
            case Instance.StartData(id, providedImage, _, callback, _) =>
              ( id must be_==(response.instanceId) ) and
              ( providedImage must be_==(testImage) ) and
              ( callback must be_==(testCallback) )
          }
        }
      }

      "instance exists after system restart" >> {
        import dit4c.common.KeyHelpers._
        val clusterId = "test-rkt"
        implicit val system =
          ActorSystem(s"RktClusterManager-StartInstance-restart")
        val resolvedImageId = "sha512-"+Stream.fill(64)("0").mkString
        val resolvedPublicKey = randomPGPPublicKeyRing
        val runnerFactory =
          (_: RktNode.ServerConnectionDetails, _: String) =>
            new RktRunner {
              override def fetch(imageName: String): Future[String] =
                Future.successful(resolvedImageId)
              override def start(
                  instanceId: String,
                  image: String,
                  portalUri: String): Future[PGPPublicKeyRing] =
                Future.successful(resolvedPublicKey)
              override def stop(instanceId: String): Future[Unit] = ???
              override def export(instanceId: String) = ???
              override def uploadImage(instanceId: String,
                  imageServer: String,
                  portalUri: String): Future[Unit] = ???
              override def resolveStates(instanceIds: Set[String]) = ???
            }

        val probe = TestProbe()
        val hostPublicKey = randomRSAPublicKey
        def createManager =
            probe.childActorOf(
                RktClusterManager.props(
                    clusterId,
                    runnerFactory,
                    mockFetchSshHostKey(hostPublicKey)))
        val manager = createManager
        // Create some nodes
        val nodeIds = 1.to(3).map { i =>
          val probe = TestProbe()
          probe.send(manager, AddRktNode(
              "", s"169.254.42.$i", 22, "testuser",
              Seq(hostPublicKey.ssh.fingerprint("SHA-256")),
              "/var/lib/dit4c/rkt").fillId)
          val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded](1.minute)
          nodeId
        }
        // Schedule an instance
        val testImage = "docker://dit4c/gotty:latest"
        val testCallback = "http://example.test/"
        probe.send(manager, StartInstance(randomInstanceId, testImage, testCallback))
        val response = probe.expectMsgType[RktClusterManager.StartingInstance](1.minute)
        (response must {
          import scala.language.experimental.macros
          matchA[RktClusterManager.StartingInstance]
            .instanceId(not(beEmpty[String]))
        }) and
        {
          probe.watch(manager)
          probe.send(manager, Shutdown)
          probe.expectMsgType[Terminated](1.minute)
          val newManager = createManager
          probe.send(newManager, GetInstanceStatus(response.instanceId))
          val instanceStatus =
            probe.expectMsgType[Instance.StatusReport](1.minute)
          instanceStatus.data must beLike {
            case Instance.StartData(id, providedImage, _, callback, key) =>
              ( id must be_==(response.instanceId) ) and
              ( providedImage must be_==(testImage) ) and
              ( callback must be_==(testCallback) )
          }
        }
      }
    }

    "SaveInstance" >> {
      "saves an instance" >> {
        import dit4c.common.KeyHelpers._
        import dit4c.scheduler.domain.{instance => i}
        val clusterId = "test-rkt"
        implicit val system =
          ActorSystem(s"RktClusterManager-SaveInstance")
        val resolvedImageId = "sha512-"+Stream.fill(64)("0").mkString
        val resolvedPublicKey = randomPGPPublicKeyRing
        val runnerFactory =
          (_: RktNode.ServerConnectionDetails, _: String) =>
            new RktRunner {
              override def fetch(imageName: String): Future[String] =
                Future.successful(resolvedImageId)
              override def start(
                  instanceId: String,
                  image: String,
                  portalUri: String): Future[PGPPublicKeyRing] =
                Future.successful(resolvedPublicKey)
              override def stop(instanceId: String): Future[Unit] =
                Future.successful(())
              override def export(instanceId: String) = Future.successful(())
              override def uploadImage(instanceId: String,
                  imageServer: String,
                  portalUri: String): Future[Unit] = Future.successful(())
              override def resolveStates(instanceIds: Set[String]) = ???
            }
        val probe = TestProbe()
        val hostPublicKey = randomRSAPublicKey
        val manager =
            probe.childActorOf(
                RktClusterManager.props(
                    clusterId,
                    runnerFactory,
                    mockFetchSshHostKey(hostPublicKey)))
        // Create some nodes
        val nodeIds = 1.to(3).map { i =>
          val probe = TestProbe()
          probe.send(manager, AddRktNode(
              "", s"169.254.42.$i", 22, "testuser",
              Seq(hostPublicKey.ssh.fingerprint("SHA-256")),
              "/var/lib/dit4c/rkt").fillId)
          val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded](1.minute)
          nodeId
        }
        // Schedule an instance
        val testImage = "docker://dit4c/gotty:latest"
        val testCallback = "http://example.test/"
        probe.send(manager, StartInstance(randomInstanceId, testImage, testCallback))
        val response = probe.expectMsgType[RktClusterManager.StartingInstance](1.minute)
        Stream.continually({
          probe.send(manager, GetInstanceStatus(response.instanceId))
          val instanceStatus = probe.expectMsgType[Instance.StatusReport](1.minute)
          instanceStatus.state
        }).filter(_ == Instance.Running).head
        // Now save the instance
        probe.send(manager, InstanceEnvelope(response.instanceId, Instance.Save("")))
        probe.expectMsgType[Instance.Ack.type](1.minute);
        {
          // Poll 10 times, 100ms apart to check if we're uploading the instance
          Stream.fill(10)({
            Thread.sleep(100)
            probe.send(manager, GetInstanceStatus(response.instanceId))
            val instanceStatus = probe.expectMsgType[Instance.StatusReport](1.minute)
            instanceStatus.state
          }).filter(_ == Instance.Uploading).headOption must beSome
        } and {
          probe.send(manager, InstanceEnvelope(response.instanceId, Instance.ConfirmUpload))
          // Poll 10 times, 100ms apart to check we've confirmed the upload and shifted state
          Stream.fill(10)({
            Thread.sleep(100)
            probe.send(manager, GetInstanceStatus(response.instanceId))
            val instanceStatus = probe.expectMsgType[Instance.StatusReport](1.minute)
            instanceStatus.state
          }).filter(_ == Instance.Uploaded).headOption must beSome
        }
      }
    }

    "DiscardInstance" >> {
      "discards an instance" >> {
        import dit4c.common.KeyHelpers._
        import dit4c.scheduler.domain.{instance => i}
        val clusterId = "test-rkt"
        implicit val system =
          ActorSystem(s"RktClusterManager-DiscardInstance")
        val log = system.log
        val resolvedImageId = "sha512-"+Stream.fill(64)("0").mkString
        val resolvedPublicKey = randomPGPPublicKeyRing
        val runnerFactory =
          (_: RktNode.ServerConnectionDetails, _: String) =>
            new RktRunner {
              override def fetch(imageName: String): Future[String] =
                Future.successful(resolvedImageId)
              override def start(
                  instanceId: String,
                  image: String,
                  portalUri: String): Future[PGPPublicKeyRing] =
                Future.successful(resolvedPublicKey)
              override def stop(instanceId: String): Future[Unit] =
                Future.successful(())
              override def export(instanceId: String) = ???
              override def uploadImage(instanceId: String,
                  imageServer: String,
                  portalUri: String): Future[Unit] = ???
              override def resolveStates(instanceIds: Set[String]) = ???
            }
        val probe = TestProbe()
        val hostPublicKey = randomRSAPublicKey
        val manager =
            probe.childActorOf(
                RktClusterManager.props(
                    clusterId,
                    runnerFactory,
                    mockFetchSshHostKey(hostPublicKey)))
        // Create some nodes
        val nodeIds = 1.to(3).map { i =>
          val probe = TestProbe()
          probe.send(manager, AddRktNode(
              "", s"169.254.42.$i", 22, "testuser",
              Seq(hostPublicKey.ssh.fingerprint("SHA-256")),
              "/var/lib/dit4c/rkt").fillId)
          val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded](1.minute)
          nodeId
        }
        // Schedule an instance
        val testImage = "docker://dit4c/gotty:latest"
        val testCallback = "http://example.test/"
        probe.send(manager, StartInstance(randomInstanceId, testImage, testCallback))
        val response = probe.expectMsgType[RktClusterManager.StartingInstance](1.minute)
        Stream.continually({
          probe.send(manager, GetInstanceStatus(response.instanceId))
          val instanceStatus = probe.expectMsgType[Instance.StatusReport](1.minute)
          instanceStatus.state
        }).filter(_ == Instance.Running).head
        // Now discard the instance
        probe.send(manager, InstanceEnvelope(response.instanceId, Instance.Discard))
        probe.expectMsgType[Instance.Ack.type](1.minute)
        // Poll 10 times, 100ms apart to check if we've discarded the instance
        Stream.fill(10)({
          Thread.sleep(100)
          probe.send(manager, GetInstanceStatus(response.instanceId))
          val instanceStatus = probe.expectMsgType[Instance.StatusReport](1.minute)
          instanceStatus.state
        }).filter(_ == Instance.Discarded).headOption must beSome
      }
    }

  }

  implicit class AddRktNodeHelper(msg: RktClusterManager.AddRktNode) {
    def fillId: RktClusterManager.AddRktNode = {
      val bestFingerprint =
        (msg.sshHostKeyFingerprints.filter(_.startsWith("SHA256:")) ++
            msg.sshHostKeyFingerprints).headOption
      val id =
        (Seq(msg.username, msg.host, msg.port.toString) ++ bestFingerprint)
          .map(java.net.URLEncoder.encode(_, "UTF-8"))
          .mkString("_")
      msg.copy(nodeId = id)
    }
  }

  def randomRSAPublicKey: RSAPublicKey = {
    val sr = SecureRandom.getInstance("SHA1PRNG")
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(512, sr)
    kpg.genKeyPair.getPublic.asInstanceOf[RSAPublicKey]
  }

  def randomPGPPublicKeyRing: PGPPublicKeyRing = {
    import dit4c.common.KeyHelpers._
    PGPKeyGenerators.RSA(Random.alphanumeric.take(20).mkString).toPublicKeyRing
  }

  def mockConfigProvider(rrc: RktRunner.Config) = new ConfigProvider {
    override def rktRunnerConfig = rrc
    override def sshKeys = Future.successful(Nil)
  }

  def mockRktRunnerFactory(
      cd: RktNode.ServerConnectionDetails, dir: String): RktRunner =
    new RktRunner {
      override def fetch(imageName: String): Future[String] = ???
      override def start(
          instanceId: String,
          image: String,
          portalUri: String): Future[PGPPublicKeyRing] = ???
      override def stop(instanceId: String): Future[Unit] = ???
      override def export(instanceId: String) = ???
      def uploadImage(instanceId: String,
          imageServer: String,
          portalUri: String): Future[Unit] = ???
      override def resolveStates(instanceIds: Set[String]) =
        if (instanceIds.isEmpty) Future.successful(Map.empty)
        else ???
    }

  def mockFetchSshHostKey(
      pk: RSAPublicKey)(host: String, port: Int): Future[RSAPublicKey] =
        Future.successful(pk)

  def randomInstanceId = Random.alphanumeric.take(20).mkString

}
