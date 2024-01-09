package escalator.util.logging

import escalator.util.disk._

object LogUtil {

	var LOG_FOLDER = "/tmp"

	def append(logName: String,message: String) = {
		FileUtil.appendLine(LOG_FOLDER + "/" + logName,message)
	}
}