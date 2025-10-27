package escalator.util.disk

import java.io.File
import java.io.FileWriter
// import java.io.IOException

object FileUtil {

  def getExtension(path: String): String = {
    if (path.contains(".")) {
      path.substring(path.lastIndexOf("."), path.size)
    } else {
      ""
    }
  }

  def exists(path: String): Boolean = {
    val f = new File(path)
    f.exists
  }

  def tree(root: File, skipHidden: Boolean = false): Stream[File] = {
    if (!root.exists || (skipHidden && root.isHidden)) Stream.empty
    else root #:: (
      root.listFiles match {
        case null => Stream.empty
        case files => files.toStream.flatMap(tree(_, skipHidden))
      })
  }

  def read(fileName: String): String = {
    val fileLines =
      try scala.io.Source.fromFile(fileName, "UTF-8").mkString catch {
        case e: java.io.FileNotFoundException => e.getLocalizedMessage()
      }
    fileLines
  }

  //dont use for big files!
  def readLines(fileName: String): List[String] = {
    val fileLines = scala.io.Source.fromFile(fileName, "UTF-8").getLines
    fileLines.toList
  }

  def write(fileName: String, text: String) = {
    try {
      val fw = new FileWriter(fileName, false)
      fw.write(text)
      fw.close()
    } catch {
      case ex: Exception => {
        ex.printStackTrace
      }
    }
  }

  def writeIfDoesNotExist(fileName: String, text: String) = {
    if (!exists(fileName)){
      write(fileName, text)
    }
  }

  def append(fileName: String, text: String) = {
    try {
      val fw = new FileWriter(fileName, true)
      fw.write(text)
      fw.close()
    } catch {
      case ex: Exception => {
        ex.printStackTrace
      }
    }
  }

  def appendLine(fileName: String, line: String) = {
    append(fileName, line + "\n")
  }

  def delete(filePath: String) = {
    try {
      val fileTemp = new File(filePath)
      if (fileTemp.exists) {
        val result = fileTemp.delete()
        // println("File delete:" + result)
      } else {
        // println("File doesnt exist!")
      }
    } catch {
      case ex: Exception => {
        ex.printStackTrace()
      }
    }
  }

  def createDirectoriesForFile(filePath: String) = {
    val fullPath = new File(filePath)
    val parentPath = new File(fullPath.getParent)
    parentPath.mkdirs
  }

  def createDirectoriesForFolder(folderPath: String) = {
    new File(folderPath).mkdirs
  }

  def getListOfFiles(dir: File, extensions: List[String]): List[File] = {
    val listOfFiles = dir.listFiles
    if (listOfFiles != null) {
      listOfFiles.filter(_.isFile).toList.filter { file =>
        extensions.exists(file.getName.endsWith(_))
      }
    } else {
      List()
    }
  }

  def renameFile(fromFile: String,toFile: String): Boolean = {
      val from = new File(fromFile)
      val to = new File(toFile)

      from.renameTo(to)
  }    

}
