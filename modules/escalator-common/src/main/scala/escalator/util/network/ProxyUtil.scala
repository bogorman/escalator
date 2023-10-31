package escalator.network

import escalator.util.disk._

case class ProxyDetails(address: String, port: Int, username: String, password: String)

object ProxyUtil {

	def loadProxies(filename: String): List[ProxyDetails] = {
		val lines = FileUtil.readLines(filename)
		println("loaded " + lines.size + " from file.")
		val proxies = lines.flatMap { line =>
			val parts = line.split(":")
			println("line:" + line)
			if (parts.size == 4) {
				Some( ProxyDetails(parts(2),parts(3).toInt,parts(0),parts(1)) )
			} else {
				None
			}
		}

		println("Loaded " + proxies.size + " proxies.")
		proxies
	}

}