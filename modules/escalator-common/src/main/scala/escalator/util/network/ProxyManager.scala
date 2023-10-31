package escalator.network

import scala.collection.mutable.{ ArrayBuffer => MList }
import scala.util.Random

object ProxyManager {
	//make config
	lazy val allProxies: List[ProxyDetails] = ProxyUtil.loadProxies("proxies.txt")

	val usedProxies = MList.empty[ProxyDetails]

	def availableProxies(): List[ProxyDetails] = {
		allProxies diff usedProxies
	}

	def nextProxy(): ProxyDetails = {
		Random.shuffle(availableProxies).head
	}

	def markAsUsed(pd: ProxyDetails) = {
		usedProxies += pd
	}

}


