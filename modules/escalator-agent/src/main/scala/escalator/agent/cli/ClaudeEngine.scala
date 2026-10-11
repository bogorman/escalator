package escalator.agent.cli

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.{Duration, Instant}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

import escalator.agent.providers.ClaudeCliConfig

/** Claude Code CLI versions used by the agent engine (from lexie / signoff / migratecode).
  *
  *  - The image ships one CLI (`bundledBin`). Admins can install newer releases from npm into
  *    `baseDir/versions/<version>`, which lives on the persistent volume.
  *  - Each install is smoke-tested through the configured proxy or key (`--version` plus a one-line prompt) before it
  *    becomes active.
  *  - Every turn resolves [[bin]] afresh, so a switch takes effect from the next turn. Running turns finish on the
  *    old binary.
  *  - npm is checked for a newer release every `checkEvery`.
  *
  * The CLI runs with the same environment as a real turn (`cli.childEnv`: config dir, proxy or API key), so the smoke
  * test proves exactly what the next turn will use. Hand turns the switchable binary with [[resolving]].
  */
final class ClaudeEngine(cli: ClaudeCliConfig, baseDir: Path, npmBin: String = "npm",
                         checkEvery: Duration = Duration.ofHours(12), keepVersions: Int = 3)(implicit ec: ExecutionContext) {
  private val bundledBin = cli.bin
  private val log = LoggerFactory.getLogger("agent.engine")
  private val Package = "@anthropic-ai/claude-code"
  private val VersionRe = """^\d+\.\d+\.\d+$""".r
  private val versionsDir = baseDir.resolve("versions")
  private val currentFile = baseDir.resolve("current")
  private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()

  import ClaudeEngine.Job
  private val latest = new AtomicReference[Option[(String, Instant)]](None)
  private val checkError = new AtomicReference[Option[String]](None)
  private val job = new AtomicReference[Option[Job]](None)
  private val bundledVersionCache = new AtomicReference[Option[String]](None)

  /** The binary turns should run now. */
  def bin: String = current().map(binOf(_).toString).getOrElse(bundledBin)

  /** The admin-selected version, if it is still installed (otherwise the bundled CLI is used). */
  def current(): Option[String] =
    Try(new String(Files.readAllBytes(currentFile), UTF_8).trim).toOption.filter(v => VersionRe.matches(v) && Files.isExecutable(binOf(v)))

  def installed(): List[String] =
    Try(Files.list(versionsDir).iterator().asScala.toList).getOrElse(Nil).map(_.getFileName.toString)
      .filter(v => VersionRe.matches(v) && Files.isExecutable(binOf(v))).sortWith(newer)

  def bundledVersion(): Option[String] = bundledVersionCache.get().orElse {
    val v = versionOf(bundledBin); bundledVersionCache.set(v); v
  }

  def activeVersion(): Option[String] = current().orElse(bundledVersion())

  /** Latest release on npm (`dist-tags.latest`). */
  def checkLatest(): Option[String] = {
    val res = Try {
      val req = HttpRequest.newBuilder(URI.create(s"https://registry.npmjs.org/$Package/latest")).timeout(java.time.Duration.ofSeconds(20)).GET().build()
      val body = http.send(req, HttpResponse.BodyHandlers.ofString()).body()
      parse(body).toOption.flatMap(_.hcursor.get[String]("version").toOption).filter(VersionRe.matches)
        .getOrElse(throw new IllegalStateException(s"unexpected npm response: ${body.take(200)}"))
    }
    res match {
      case Success(v) => latest.set(Some(v -> Instant.now())); checkError.set(None); Some(v)
      case Failure(e) => log.warn(s"claude-code version check failed: ${e.getMessage}"); checkError.set(Some(e.getMessage)); latest.get().map(_._1)
    }
  }

  def updateAvailable: Option[String] = for {
    (l, _) <- latest.get()
    a      <- activeVersion().orElse(Some("0.0.0"))
    if newer(l, a)
  } yield l

  /** Install `version` from npm, smoke-test it, and make it active only if the test passes. One job at a time. */
  def upgrade(version: String): Either[String, Unit] =
    if (!VersionRe.matches(version)) Left(s"invalid version: $version")
    else if (job.get().exists(_.running)) Left("another install is already running")
    else {
      set(version, "installing", running = true, None, s"npm install $Package@$version")
      Future {
        val dir = versionsDir.resolve(version)
        val steps: Try[String] = for {
          _ <- if (Files.isExecutable(binOf(version))) Success("already installed") else npmInstall(version, dir)
          _ = set(version, "testing", running = true, None, "claude --version and a test prompt")
          v <- versionOf(binOf(version).toString).toRight(new IllegalStateException("claude --version failed")).toTry
          _ <- if (v == version) Success(()) else Failure(new IllegalStateException(s"installed $v, expected $version"))
          r <- smokeTest(binOf(version).toString)
        } yield r
        steps match {
          case Success(answer) =>
            use(Some(version))
            prune()
            set(version, "done", running = false, Some(true), s"Active. Test prompt answered: ${answer.take(80)}")
            log.info(s"claude-code $version installed and active")
          case Failure(e) =>
            set(version, "failed", running = false, Some(false), e.getMessage.take(500))
            log.warn(s"claude-code $version upgrade failed: ${e.getMessage}")
        }
      }
      Right(())
    }

  /** Switch to an installed version, or back to the bundled CLI (`None`). */
  def use(version: Option[String]): Either[String, Unit] = version match {
    case Some(v) if !installed().contains(v) => Left(s"$v is not installed")
    case Some(v) =>
      Files.createDirectories(baseDir)
      val tmp = baseDir.resolve(s"current.${System.nanoTime()}")
      Files.write(tmp, v.getBytes(UTF_8))
      Files.move(tmp, currentFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      Right(())
    case None => Files.deleteIfExists(currentFile); Right(())
  }

  def status(): Json = Json.obj(
    "active_version" -> activeVersion().asJson,
    "using" -> current().fold("bundled")(_ => "installed").asJson,
    "bin" -> bin.asJson,
    "bundled" -> Json.obj("bin" -> bundledBin.asJson, "version" -> bundledVersion().asJson),
    "installed" -> installed().asJson,
    "latest" -> latest.get().map(_._1).asJson,
    "checked_at" -> latest.get().map(_._2.toString).asJson,
    "check_error" -> checkError.get().asJson,
    "update_available" -> updateAvailable.asJson,
    "job" -> job.get().map(j => Json.obj("version" -> j.version.asJson, "step" -> j.step.asJson, "running" -> j.running.asJson,
      "ok" -> j.ok.asJson, "message" -> j.message.asJson, "at" -> j.at.toString.asJson)).asJson
  )

  /** Periodic npm check; `schedule(initialDelay, interval)(task)` comes from the host app (e.g. a Pekko scheduler). */
  def start(schedule: (scala.concurrent.duration.FiniteDuration, scala.concurrent.duration.FiniteDuration) => (() => Unit) => Unit): Unit = {
    import scala.concurrent.duration._
    schedule(1.minute, checkEvery.toMinutes.minutes) { () =>
      checkLatest().filter(_ => updateAvailable.isDefined).foreach(v => log.info(s"claude-code $v is available (active: ${activeVersion().getOrElse("?")})"))
    }
  }

  // ---------- internals ----------
  private def binOf(v: String): Path = versionsDir.resolve(v).resolve("node_modules").resolve(".bin").resolve("claude")

  private def set(v: String, step: String, running: Boolean, ok: Option[Boolean], msg: String): Unit =
    job.set(Some(Job(v, step, running, ok, msg, Instant.now())))

  private def newer(a: String, b: String): Boolean = {
    def parts(s: String) = s.split('.').toList.map(_.toIntOption.getOrElse(0))
    parts(a).zipAll(parts(b), 0, 0).find { case (x, y) => x != y }.exists { case (x, y) => x > y }
  }

  private def run(cmd: List[String], cwd: Option[Path], timeoutSec: Long): Try[String] = Try {
    val pb = new ProcessBuilder(cmd.asJava).redirectErrorStream(true)
    cwd.foreach(d => pb.directory(d.toFile))
    pb.environment().clear()
    cli.childEnv.foreach { case (k, v) => pb.environment().put(k, v) }
    val p = pb.start()
    p.getOutputStream.close()
    val out = Future(new String(p.getInputStream.readAllBytes(), UTF_8))
    if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException(s"${cmd.head} timed out after ${timeoutSec}s") }
    val text = scala.concurrent.Await.result(out, scala.concurrent.duration.Duration(5, "s"))
    if (p.exitValue() != 0) throw new IllegalStateException(s"${cmd.take(3).mkString(" ")} exited ${p.exitValue()}: ${text.takeRight(400)}")
    text
  }

  private def versionOf(binary: String): Option[String] =
    run(List(binary, "--version"), None, 30).toOption.flatMap(_.trim.split("\\s+").headOption).filter(VersionRe.matches)

  private def npmInstall(version: String, dir: Path): Try[String] = {
    Files.createDirectories(dir)
    run(List(npmBin, "install", "--prefix", dir.toString, "--no-audit", "--no-fund", "--loglevel=error", s"$Package@$version"), None, 600)
      .recoverWith { case e => Try(deleteTree(dir)); Failure(e) }
  }

  private def smokeTest(binary: String): Try[String] = {
    val tmp = Files.createTempDirectory("claude-smoke")
    val r = run(List(binary, "-p", "Reply with just: ok"), Some(tmp), 180).flatMap { out =>
      if (out.toLowerCase.contains("ok")) Success(out.trim) else Failure(new IllegalStateException(s"unexpected test answer: ${out.take(300)}"))
    }
    Try(deleteTree(tmp))
    r
  }

  /** Keep the active version plus the newest `keepVersions` installs. */
  private def prune(): Unit = {
    val keep = installed().take(keepVersions).toSet ++ current()
    installed().filterNot(keep).foreach(v => Try(deleteTree(versionsDir.resolve(v))))
  }

  private def deleteTree(p: Path): Unit =
    if (Files.exists(p)) Files.walk(p).iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
}

object ClaudeEngine {
  /** `cli` with its binary resolved through `engine` on every turn. */
  def resolving(cli: ClaudeCliConfig, engine: ClaudeEngine): ClaudeCliConfig = cli.copy(binResolver = Some(() => engine.bin))

  final case class Job(version: String, step: String, running: Boolean, ok: Option[Boolean], message: String, at: Instant)
}
