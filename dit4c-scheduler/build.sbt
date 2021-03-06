import SharedDependencyVersions._

name := "dit4c-scheduler"

crossScalaVersions := Nil

scalacOptions := Seq(
  "-unchecked",
  "-deprecation",
  "-encoding", "utf8",
  "-Xfatal-warnings")

libraryDependencies ++= {
  Seq(
    "com.trueaccord.scalapb" %% "scalapb-runtime" % scalapbV % "protobuf",
    "ch.qos.logback"      %   "logback-classic"       % logbackV,
    "com.typesafe.akka"   %%  "akka-http"             % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-actor"            % akkaV,
    "com.typesafe.akka"   %%  "akka-persistence"      % akkaV,
    "com.typesafe.akka"   %%  "akka-remote"           % akkaV,
    "com.typesafe.akka"   %%  "akka-slf4j"            % akkaV,
    "com.typesafe.akka"   %%  "akka-persistence-cassandra" % "0.22",
    "com.twitter"         %%  "chill-akka"            % chillV,
    "com.jcraft"          %   "jsch"                  % "0.1.53",
    "com.github.scopt"    %%  "scopt"                 % "3.4.0",
    "de.heikoseeberger"   %%  "akka-http-play-json"   % "1.7.0",
  	"com.iheart"          %%  "ficus"                 % ficusV,
    "org.specs2"          %%  "specs2-core"           % specs2V % "test",
    "org.specs2"          %%  "specs2-matcher-extra"  % specs2V % "test",
    "org.specs2"          %%  "specs2-scalacheck"     % specs2V % "test",
    "com.typesafe.akka"   %%  "akka-http-testkit"     % akkaHttpV % "test",
    "com.github.dnvriend" %% "akka-persistence-inmemory" % "1.3.0" % "test",
    "org.apache.sshd"     %   "apache-sshd"           % "1.2.0" % "test"
      exclude("org.slf4j", "slf4j-jdk14")
  )
}

fork := true

scalacOptions ++= Seq("-feature")

mainClass in Compile := Some("dit4c.scheduler.Main")

managedSourceDirectories in Compile += target.value / "protobuf-generated"

PB.targets in Compile := Seq(
  scalapb.gen(grpc = false) -> (target.value / "protobuf-generated")
)

coverageExcludedPackages := "dit4c\\.scheduler\\.runner.*"

// Download rkt for testing
resourceGenerators in Test <+=
  (resourceManaged in Test, name, version, streams) map { (dir, n, v, s) =>
    import scala.sys.process._
    val rktVersion = "1.25.0"
    val rktDir = dir / "rkt"
    val rktExecutable = rktDir / "rkt"
    s.log.debug(s"Checking for rkt $rktVersion tarball")
    if (!rktDir.isDirectory || !(rktExecutable).isFile) {
      IO.withTemporaryDirectory { tmpDir =>
        val rktTarballUrl = new java.net.URL(
          s"https://github.com/coreos/rkt/releases/download/v${rktVersion}/rkt-v${rktVersion}.tar.gz")
        val rktTarball = tmpDir / s"rkt-v${rktVersion}.tar.gz"
        s.log.info(s"Downloading rkt $rktVersion")
        IO.download(rktTarballUrl, rktTarball)
        val sbtLogger = ProcessLogger(s.log.info(_), s.log.warn(_))
        Process(
          Seq("tar", "xzf", rktTarball.getAbsolutePath),
          cwd=Some(tmpDir)).!<(sbtLogger)
        IO.delete(rktDir)
        IO.copyDirectory(tmpDir / s"rkt-v${rktVersion}", rktDir)
        IO.delete(tmpDir)
      }
    }
    IO.listFiles(rktDir).toSeq
  }
