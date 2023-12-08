package escalator.util.logging

import escalator.util.disk._

object LogUtil {

	var LOG_FOLDER = "/tmp"

	def append(logName: String,message: String) = {
		FileUtil.append(LOG_FOLDER + "/" + logName,message)
	}
}