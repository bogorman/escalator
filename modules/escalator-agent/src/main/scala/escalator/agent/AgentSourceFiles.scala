package escalator.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

/**
 * The agent names its own source file (e.g. Report.md), so we detect
 * it by extension rather than a fixed name. Reserved tooling files are excluded.
 */
object AgentSourceFiles {

  private val reserved = Set("agent.log", "agent.stderr.log", "session.json", "AGENTS.md")

  /** Top-level source files in `dir` matching any extension → fileName -> contents. */
  def list(dir: Path, exts: Seq[String]): Map[String, String] = {
    if (!Files.isDirectory(dir)) Map.empty
    else {
      val stream = Files.list(dir)
      try {
        stream.iterator().asScala
          .filter(Files.isRegularFile(_))
          .map(p => p.getFileName.toString)
          .filter(n => !reserved.contains(n) && exts.exists(n.endsWith))
          .flatMap { n =>
            try Some(n -> new String(Files.readAllBytes(dir.resolve(n)), StandardCharsets.UTF_8))
            catch { case _: Throwable => None }
          }
          .toMap
      } finally stream.close()
    }
  }
}
