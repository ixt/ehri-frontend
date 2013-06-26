package rest

import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import play.api.libs.ws.WS
import defines.EntityType
import models._
import play.api.libs.json.{Reads, Json}


/**
 * Data Access Object for fetching link data.
 *
 * @param userProfile
 */
case class LinkDAO(userProfile: Option[UserProfileMeta] = None) extends RestDAO {

  implicit val entityReads = Entity.entityReads
  implicit val entityPageReads = PageReads.pageReads
  import EntityDAO._

  final val BODY_PARAM = "body"
  final val BODY_TYPE = "bodyType"
  final val BODY_NAME = "bodyName"

  def requestUrl = "http://%s:%d/%s/%s".format(host, port, mount, EntityType.Link)

  implicit val linkMetaReads = LinkMeta.Converter.restReads

  /**
   * Fetch links for the given item.
   * @param id
   * @return
   */
  def getFor(id: String): Future[Either[RestError, List[LinkMeta]]] = {

    WS.url(enc(requestUrl, "for/%s?limit=1000".format(id)))
      .withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkError(response).right.map { r =>
        r.json.validate[List[LinkMeta]](Reads.list(linkMetaReads)).fold(
          valid = {
            lst => lst
          },
          invalid = { e =>
            println(r.json)
            sys.error("Unable to decode list result: " + e.toString)
          }
        )
      }
    }
  }

  /**
   * Create a single link.
   * @param id
   * @param src
   * @param link
   * @return
   */
  def link(id: String, src: String, link: LinkF, accessPoint: Option[String] = None): Future[Either[RestError, LinkMeta]] = {
    WS.url(enc(requestUrl, id, accessPoint.map(ap => s"${src}?${BODY_PARAM}=${ap}").getOrElse(src)))
      .withHeaders(authHeaders.toSeq: _*)
      .post(Json.toJson(link)).map { response =>
      checkError(response).right.map(r => r.json.as[LinkMeta](linkMetaReads))
    }
  }

  /**
   * Remove a link on an item.
   */
  def deleteLink(id: String, linkId: String): Future[Either[RestError,Boolean]] = {
    WS.url(enc(requestUrl, "for", id, linkId))
      .withHeaders(authHeaders.toSeq: _*)
      .delete.map { response =>
      checkError(response).right.map { r =>
        EntityDAO.handleDelete(linkId)
        true
      }
    }
  }


  /**
   * Remove an access point for a given item.
   * TODO: When the linking api gets sorted out, move
   * this somewhere better.
   */
  def deleteAccessPoint(id: String): Future[Either[RestError,Boolean]] = {
    WS.url(enc(requestUrl, "accessPoint", id))
      .withHeaders(authHeaders.toSeq: _*)
      .delete.map { response =>
      checkError(response).right.map { r =>
        EntityDAO.handleDelete(id)
        true
      }
    }
  }

  /**
   * Create multiple links. NB: This function is NOT transactional.
   * @param id
   * @param srcToLinks list of ids, link object tuples
   * @return
   */
  def linkMultiple(id: String, srcToLinks: List[(String,LinkF,Option[String])]): Future[Either[RestError, List[LinkMeta]]] = {
    val res = Future.sequence {
      srcToLinks.map {
        case (other, ann, accessPoint) => link(id, other, ann, accessPoint)
      }
    }
    res.map { (lst: List[Either[RestError,LinkMeta]]) =>
      // If there was an error, pluck the first one out and
      // return it... ignore the rest
      lst.filter(_.isLeft).map(_.left.get).headOption.map { err =>
        Left(err)
      } getOrElse {
        Right(lst.map(_.right.get))
      }
    }
  }
}