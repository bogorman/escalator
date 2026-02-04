package escalator.db.generators

import java.security.MessageDigest
import java.io.File
import scala.io.Source

/**
 * Utility for generating and verifying checksums on auto-generated files
 * to track whether user-editable files have been modified.
 */
object ChecksumUtil {

  private val CHECKSUM_MARKER = "// GENERATOR_CHECKSUM:"

  /**
   * Calculate MD5 checksum of a string
   */
  def calculateMD5(content: String): String = {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(content.getBytes("UTF-8"))
    digest.map("%02x".format(_)).mkString
  }

  /**
   * Add checksum as a comment on the last line of generated code
   *
   * @param content The generated code content
   * @return Content with checksum appended as last line
   */
  def addChecksum(content: String): String = {
    val checksum = calculateMD5(content)
    content + s"\n$CHECKSUM_MARKER$checksum\n"
  }

  /**
   * Extract content without the checksum line
   *
   * @param fullContent The full file content including checksum
   * @return Tuple of (content without checksum, optional checksum value)
   */
  def extractChecksum(fullContent: String): (String, Option[String]) = {
    val lines = fullContent.split("\n")

    // Check if last line contains checksum
    if (lines.nonEmpty && lines.last.trim.startsWith(CHECKSUM_MARKER)) {
      val checksumLine = lines.last.trim
      val checksum = checksumLine.stripPrefix(CHECKSUM_MARKER).trim
      val contentWithoutChecksum = lines.dropRight(1).mkString("\n")
      (contentWithoutChecksum, Some(checksum))
    } else {
      // No checksum found
      (fullContent, None)
    }
  }

  /**
   * Check if a file has been modified by comparing its current checksum
   * with the stored checksum.
   *
   * @param filePath Path to the file to check
   * @return Some(true) if modified, Some(false) if unmodified, None if no checksum found or file doesn't exist
   */
  def isFileModified(filePath: String): Option[Boolean] = {
    val file = new File(filePath)
    if (!file.exists()) {
      println(s"[DEBUG] ChecksumUtil.isFileModified: File doesn't exist: $filePath")
      return None
    }

    try {
      val fullContent = Source.fromFile(file, "UTF-8").mkString
      val (contentWithoutChecksum, checksumOpt) = extractChecksum(fullContent)

      checksumOpt match {
        case Some(storedChecksum) =>
          val currentChecksum = calculateMD5(contentWithoutChecksum)
          val isModified = currentChecksum != storedChecksum
          println(s"[DEBUG] ChecksumUtil.isFileModified: $filePath")
          println(s"[DEBUG]   Stored checksum:  $storedChecksum")
          println(s"[DEBUG]   Current checksum: $currentChecksum")
          println(s"[DEBUG]   Is modified: $isModified")
          Some(isModified)
        case None =>
          println(s"[DEBUG] ChecksumUtil.isFileModified: No checksum found in: $filePath")
          None
      }
    } catch {
      case e: Exception =>
        println(s"[DEBUG] ChecksumUtil.isFileModified: Exception reading file: $filePath - ${e.getMessage}")
        None
    }
  }

  /**
   * Check if a file is safe to delete (unmodified user-editable file)
   *
   * @param filePath Path to the file to check
   * @return true if file is unmodified and can be safely deleted
   */
  def isSafeToDelete(filePath: String): Boolean = {
    isFileModified(filePath) match {
      case Some(false) =>
        println(s"[DEBUG] ChecksumUtil: File is UNMODIFIED and safe to delete: $filePath")
        true  // Unmodified - safe to delete
      case Some(true) =>
        println(s"[DEBUG] ChecksumUtil: File is MODIFIED, keeping: $filePath")
        false
      case None =>
        println(s"[DEBUG] ChecksumUtil: No checksum found or file doesn't exist: $filePath")
        false          // Modified, no checksum, or doesn't exist - don't delete
    }
  }

  /**
   * Get all auto-generated files that are safe to delete (unmodified)
   *
   * @param directory Directory to search
   * @param recursive Whether to search recursively
   * @return List of file paths that are safe to delete
   */
  def findDeletableFiles(directory: String, recursive: Boolean = true): List[String] = {
    val dir = new File(directory)
    if (!dir.exists() || !dir.isDirectory) {
      return List.empty
    }

    def scanDirectory(dir: File): List[File] = {
      val files = dir.listFiles()
      if (files == null) return List.empty

      val scalaFiles = files.filter(f => f.isFile && f.getName.endsWith(".scala")).toList
      val subdirs = if (recursive) files.filter(_.isDirectory).toList else List.empty

      scalaFiles ++ subdirs.flatMap(scanDirectory)
    }

    scanDirectory(dir)
      .map(_.getAbsolutePath)
      .filter(isSafeToDelete)
  }

  /**
   * Delete all unmodified auto-generated files in a directory
   *
   * @param directory Directory to clean
   * @param recursive Whether to search recursively
   * @param dryRun If true, only report what would be deleted without actually deleting
   * @return List of deleted (or would-be-deleted) file paths
   */
  def deleteUnmodifiedFiles(
      directory: String,
      recursive: Boolean = true,
      dryRun: Boolean = false
  ): List[String] = {
    val deletableFiles = findDeletableFiles(directory, recursive)

    if (!dryRun) {
      deletableFiles.foreach { filePath =>
        try {
          new File(filePath).delete()
          println(s"Deleted unmodified generated file: $filePath")
        } catch {
          case e: Exception =>
            println(s"Failed to delete $filePath: ${e.getMessage}")
        }
      }
    } else {
      deletableFiles.foreach { filePath =>
        println(s"Would delete: $filePath")
      }
    }

    deletableFiles
  }
}
