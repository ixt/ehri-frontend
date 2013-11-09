package rest

import models.{UserProfile, Entity}
import play.api.{Logger, Play}
import play.api.http.HeaderNames
import play.api.http.ContentTypes
import play.api.libs.ws.{WS, Response}
import play.api.data.validation.{ValidationError => PlayValidationError}
import play.api.libs.json.util._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.ws.WS.WSRequestHolder


sealed trait RestError extends Throwable
case class PermissionDenied(
  user: Option[String] = None,
  permission: Option[String] = None,
  item: Option[String] = None,
  scope: Option[String] = None
) extends RestError
case class ValidationError(errorSet: ErrorSet) extends RestError
case class DeserializationError() extends RestError
case class IntegrityError() extends RestError
case class ItemNotFound(
  key: Option[String] = None,
  value: Option[String] = None,
  message: Option[String] = None
) extends RestError
case class ServerError(error: String) extends RestError
case class CriticalError(error: String) extends RestError
case class BadJson(error: Seq[(JsPath,Seq[PlayValidationError])]) extends RestError {
  override def toString = Json.prettyPrint(JsError.toFlatJson(error))
}

object ItemNotFound {
  val itemNotFoundReads: Reads[ItemNotFound] = (
    (__ \ "details" \ "key").readNullable[String] and
    (__ \ "details" \ "value").readNullable[String] and
    (__ \ "details" \ "message").readNullable[String]
  )(ItemNotFound.apply _)

  val itemNotFoundWrites: Writes[ItemNotFound] = (
    (__ \ "key").writeNullable[String] and
    (__ \ "value").writeNullable[String] and
    (__ \ "message").writeNullable[String]
  )(unlift(ItemNotFound.unapply _))

  implicit val itemNotFoundFormat = Format(itemNotFoundReads, itemNotFoundWrites)
}


object PermissionDenied {
  val permissionDeniedReads: Reads[PermissionDenied] = (
    (__ \ "details" \ "accessor").readNullable[String] and
    (__ \ "details" \ "permission").readNullable[String] and
    (__ \ "details" \ "item").readNullable[String] and
    (__ \ "details" \ "scope").readNullable[String]
  )(PermissionDenied.apply _)
  
  val permissionDeniedWrites: Writes[PermissionDenied] = (
    (__ \ "accessor").writeNullable[String] and
    (__ \ "permission").writeNullable[String] and
    (__ \ "item").writeNullable[String] and
    (__ \ "scope").writeNullable[String]
  )(unlift(PermissionDenied.unapply _))

  implicit val permissionDeniedFormat = Format(
      permissionDeniedReads, permissionDeniedWrites)
}


/**
 * Structure that holds a set of errors for an entity and its
 * subtree relations.
 * 
 */
object ErrorSet {

  import play.api.libs.json.Reads._
  import play.api.libs.json.util._
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  implicit val errorReads: Reads[ErrorSet] = (
    (__ \ "errors").lazyRead(map[List[String]]) and
    (__ \ Entity.RELATIONSHIPS).lazyRead(
      map[List[Option[ErrorSet]]](list(optionNoError(errorReads)))))(ErrorSet.apply _)
}

case class ErrorSet(
  errors: Map[String,List[String]],
  relationships: Map[String,List[Option[ErrorSet]]]
) {

  /**
   * Given a persistable class, unfurl the nested errors so that they
   * conform to members of this class's form fields.
   */
  def errorsFor: Map[String,List[String]] = {
    // TODO: Handle nested errors
    errors
  }
}

/**
 * Wrapper for Auth user id string
 */
case class ApiUser(id: Option[String] = None) {
  override def toString = id.getOrElse(None.toString)
}


trait RestDAO {

  import Constants._
  import play.api.http.Status._

  /**
   * Header to add for log messages.
   */
  def msgHeader(msg: Option[String]): Seq[(String,String)] = msg.map(m => Seq(LOG_MESSAGE_HEADER_NAME -> m)).getOrElse(Seq[(String,String)]())

  /**
   * Join params into a query string
   */
  def joinQueryString(qs: Map[String, Seq[String]]): String = {
    import java.net.URLEncoder
    qs.map { case (key, vals) => {
      vals.map(v => "%s=%s".format(key, URLEncoder.encode(v, "UTF-8")))
    }}.flatten.mkString("&")
  }

  /**
   * Standard headers we sent to every Neo4j/EHRI Server request.
   */
  val headers = Map(
    HeaderNames.ACCEPT -> ContentTypes.JSON,
    HeaderNames.CONTENT_TYPE -> ContentTypes.JSON
  )

  /**
   * Headers to add to outgoing request...
   * @return
   */
  def authHeaders(implicit apiUser: ApiUser): Map[String, String] = apiUser.id match {
    case Some(id) => (headers + (AUTH_HEADER_NAME -> id))
    case None => headers
  }

  /**
   * Create a web request with correct auth parameters for the REST API.
   */
  def userCall(url: String)(implicit apiUser: ApiUser): WSRequestHolder = {
    Logger.logger.debug("[{} {}] {}", apiUser, this.getClass.getCanonicalName, url)
    WS.url(url).withHeaders(authHeaders.toSeq: _*)
  }

  /**
   * Encode a bunch of URL parts.
   */
  def enc(s: Any*) = {
    import java.net.URI
    val url = new java.net.URL(s.mkString("/"))
    val uri: URI = new URI(url.getProtocol, url.getUserInfo, url.getHost, url.getPort, url.getPath, url.getQuery, url.getRef);
    uri.toString
  }

  lazy val host: String = Play.current.configuration.getString("neo4j.server.host").get
  lazy val port: Int = Play.current.configuration.getInt("neo4j.server.port").get
  lazy val mount: String = Play.current.configuration.getString("neo4j.server.endpoint").get

  protected def checkError(response: Response): Response = {
    Logger.logger.trace("Response body ! : {}", response.body)
    response.status match {
      case OK | CREATED => response
      case e => e match {

        case UNAUTHORIZED => {
          response.json.validate[PermissionDenied].fold(
            err => throw PermissionDenied(),
            perm => {
              Logger.logger.error("Permission denied error! : {}", response.json)
              throw perm
            }
          )
        }
        case BAD_REQUEST => response.json.validate[ErrorSet].fold(
          e => {
          // Temporary approach to handling random Deserialization errors.
          // In practice this should happen
            if ((response.json \ "error").asOpt[String] == Some("DeserializationError")) {
              Logger.logger.error("Derialization error! : {}", response.json)
              throw DeserializationError()
            } else {
              throw sys.error(s"Unexpected BAD REQUEST: ${e} \n${response.body}")
            }
          },
          errorSet => {
            Logger.logger.error("ValidationError ! : {}", response.json)
            throw ValidationError(errorSet)
          }
        )
        case NOT_FOUND => {
          Logger.logger.error("404: {} -> {}", Array(response.ahcResponse.getUri, response.body))
          response.json.validate[ItemNotFound].fold(
            e => throw new ItemNotFound(),
            err => throw err
          )
        }
        case _ => {
          Logger.logger.error(response.body)
          sys.error(response.body)
        }
      }
    }
  }

  private[rest] def checkErrorAndParse[T](response: Response)(implicit reader: Reads[T]): T = {
    jsonReadToRestError(checkError(response).json, reader)
  }

  private[rest] def jsonReadToRestError[T](json: JsValue, reader: Reads[T]): T = {
    json.validate(reader).asEither match {
      case Right(ok) => ok
      case Left(err) => throw BadJson(err)
    }
  }
}