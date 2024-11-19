package escalator.util

// import com.ccadllc.cedi.config.{ConfigErrors, ConfigParser}
// import com.ccadllc.cedi.config.ConfigParser.DerivedConfigParser

import pureconfig._
import pureconfig.error.ConfigReaderFailures
import pureconfig.generic.auto._

import com.typesafe.config.Config

object Configuration {
  // def fetch[A: DerivedConfigParser](path: String*)(implicit config: Config): Either[ConfigErrors, A] = {
  //   // println("Configuration fetch path:" + path)
  //   ConfigParser.derived[A].under(path.mkString("."), failFast = true).parse(config)
  // }

  def fetch[A: ConfigReader](path: String*)(implicit config: Config): Either[ConfigReaderFailures, A] = {
    val configPath = path.mkString(".")
    ConfigSource.fromConfig(config).at(configPath).load[A]
  }

  // def stringList(path: String)(implicit config: Config): Either[ConfigErrors, List[String]] = {
  //   // println("Configuration stringList path:" + path)
  //   // ConfigParser.stringList(path).parse(config)
  // }
}
