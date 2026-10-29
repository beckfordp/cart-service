val scala3Version = "3.9.0"

val catsEffectVersion = "3.7.0"
val http4sVersion = "0.23.37"
val circeVersion = "0.14.16"
val munitVersion = "1.3.6"
val munitCatsEffectVersion = "2.2.1"
val munitScalacheckVersion = "1.3.1"
val log4catsVersion = "2.8.0"
val tapirVersion = "1.11.25"
val pureconfigVersion = "0.17.10"
val testcontainersScalaVersion = "0.43.6"
val redis4catsVersion = "2.0.6"
// Pinned to match the purerestlib version this service is built against — see
// README's "Consuming purerest as a dependency" section.
val purerestlibVersion = "0.1.0"

lazy val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "cart-service",
    scalaVersion := scala3Version,
    version := "0.1.0",
    // purerestlib resolved as an ordinary published artifact from GitHub
    // Packages, not a source/`.dependsOn` link back to the purerest repo —
    // the same real-external-consumer proof purerest's own smoke-test/
    // module established. Requires GITHUB_ACTOR/GITHUB_TOKEN (a PAT with
    // read:packages scope) in the environment.
    resolvers += "GitHub Packages" at "https://maven.pkg.github.com/beckfordp/purerest",
    credentials += Credentials(
      "GitHub Package Registry",
      "maven.pkg.github.com",
      sys.env.getOrElse("GITHUB_ACTOR", ""),
      sys.env.getOrElse("GITHUB_TOKEN", "")
    ),
    // Docker image for local dev via `docker compose up` — see docker-compose.yml.
    Docker / packageName := "cart-service",
    dockerBaseImage := "eclipse-temurin:21-jre",
    dockerUpdateLatest := true,
    dockerExposedPorts := Seq(8080, 9090),
    Universal / javaOptions += "-Dlogback.configurationFile=logback-docker.xml",
    libraryDependencies ++= Seq(
      "io.github.beckfordp" %% "purerestlib" % purerestlibVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion,
      // redis4cats: cats-effect-native Redis client - this service's persistence
      // layer (cart-service is Redis-only, see gluon/docs/system-design.md).
      "dev.profunktor" %% "redis4cats-effects" % redis4catsVersion,
      "dev.profunktor" %% "redis4cats-log4cats" % redis4catsVersion,
      // pureconfig: loads application.conf into typed config case classes.
      "com.github.pureconfig" %% "pureconfig-core" % pureconfigVersion,
      // munit: test framework used across this project (Scala-native, no JUnit dependency).
      "org.scalameta" %% "munit" % munitVersion % Test,
      // munit-cats-effect: lets test bodies return IO[Unit] and run under munit directly.
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
      // munit-scalacheck: property-based tests for pure cart rules.
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test,
      // testcontainers-scala: spins up a real, ephemeral Redis container for
      // integration tests (not used by main code).
      "com.dimafeng" %% "testcontainers-scala-redis" % testcontainersScalaVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-munit" % testcontainersScalaVersion % Test,
      // log4cats-testing: purerestlib keeps this Test-scoped (doesn't propagate to
      // consumers), so this service declares its own copy to assert on log output
      // (StructuredTestingLogger) in its own tests.
      "org.typelevel" %% "log4cats-testing" % log4catsVersion % Test
    )
  )
