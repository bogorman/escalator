package escalator.util.json

import com.fasterxml.jackson.databind.{ PropertyNamingStrategy, ObjectMapper }
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.databind._

object JsonUtil {

  def createStandardMapper(): ObjectMapper = {
      val mapper = new ObjectMapper 
      mapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING)
      mapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING)
      mapper
  }

  val mapper = createStandardMapper
  mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
  mapper.registerModule(new DefaultScalaModule)

  def toJson(data: Any) = {
    val json = mapper.writeValueAsString(data)
    json
  }

  def fromJson[T: scala.reflect.Manifest](json: String): T = {
    mapper.readValue(json, scala.reflect.classTag[T].runtimeClass).asInstanceOf[T]
  }

}