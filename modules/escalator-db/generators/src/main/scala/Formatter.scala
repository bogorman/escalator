package escalator.db.generators

import better.files.File
import org.scalafmt.interfaces.Scalafmt

object Formatter {

  val format: String => String = {
    val scalafmt = Scalafmt.create(getClass.getClassLoader)
    val config = File(".scalafmt.conf")
    val file = File("Formatter.scala")

    scalafmt.format(config.path, file.path, _)
  }

}
