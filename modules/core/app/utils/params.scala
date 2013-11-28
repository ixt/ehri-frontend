package utils

import play.api.mvc.RequestHeader
import play.api.data.Form
import play.api.data.Forms._
import backend.rest.Constants._
import eu.ehri.project.definitions.EventTypes
import defines.{EntityType, EventType}
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import utils.SystemEventParams.ShowType

/**
 * A list offset and limit.
 */
case class ListParams(offset: Int = 0, limit: Int = DEFAULT_LIST_LIMIT) {
  /**
   * The REST interface follows a convention whereby a limit of -1
   * returns ALL items.
   */
  def withoutLimit = copy(limit = -1)

  def toSeq: Seq[(String,String)]
      = (List(OFFSET_PARAM -> offset.toString) ::: List(LIMIT_PARAM -> limit.toString)).toSeq
}

object ListParams {

  def empty: ListParams = new ListParams()

  def fromRequest(request: RequestHeader, namespace: String = ""): ListParams = {
    Form(
      mapping(
        namespace + OFFSET_PARAM -> default(number, 0),
        namespace + LIMIT_PARAM -> default(number, DEFAULT_LIST_LIMIT)
      )(ListParams.apply)(ListParams.unapply)
    ).bindFromRequest(request.queryString).value.getOrElse(new ListParams())
  }
}

object PageParams {

  def empty: PageParams = new PageParams()

  def fromRequest(request: RequestHeader, namespace: String = ""): PageParams = {
    Form(
      mapping(
        namespace + PAGE_PARAM -> default(number, 1),
        namespace + LIMIT_PARAM -> default(number, DEFAULT_LIST_LIMIT)
      )(PageParams.apply)(PageParams.unapply)
    ).bindFromRequest(request.queryString).value.getOrElse(new PageParams())
  }
}

/**
 * Class for handling page parameter data
 */
case class PageParams(page: Int = 1, limit: Int = DEFAULT_LIST_LIMIT) {
  def offset: Int = (page - 1) * limit
  def range: String = s"$offset-${offset + limit}"

  def toSeq: Seq[(String,String)]
        = (List(OFFSET_PARAM -> offset.toString) ::: List(LIMIT_PARAM -> limit.toString)).toSeq
}


case class SystemEventParams(
  users: List[String] = Nil,
  eventTypes: List[EventType.Value] = Nil,
  itemTypes: List[EntityType.Value] = Nil,
  from: Option[DateTime] = None,
  to: Option[DateTime] = None,
  show: Option[ShowType.Value] = None) {
  import SystemEventParams._
  private val fmt = ISODateTimeFormat.dateTime.withZoneUTC

  def toSeq: Seq[(String,String)] = {
    (users.filterNot(_.isEmpty).map(u => USERS -> u) :::
      eventTypes.map(et => EVENT_TYPE -> et.toString) :::
      itemTypes.map(et => ITEM_TYPE -> et.toString) :::
      from.map(f => FROM -> fmt.print(f)).toList :::
      to.map(t => TO -> fmt.print(t)).toList :::
      show.map(f => SHOW -> f.toString).toList).toSeq
  }
}

object SystemEventParams {

  def empty: SystemEventParams = new SystemEventParams()

  val SHOW = "show"
  object ShowType extends Enumeration {
    type Type = Value
    val All = Value("all")
    val Watched = Value("watched")
    val Follows = Value("follows")
  }

  def form: Form[SystemEventParams] = Form(
    mapping(
      USERS -> list(text),
      EVENT_TYPE -> list(models.forms.enum(EventType)),
      ITEM_TYPE -> list(models.forms.enum(EntityType)),
      FROM -> optional(jodaDate(pattern = DATE_PATTERN)),
      TO -> optional(jodaDate(pattern = DATE_PATTERN)),
      SHOW -> optional(models.forms.enum(ShowType))
    )(SystemEventParams.apply _)(SystemEventParams.unapply _)
  )

  def fromRequest(request: RequestHeader): SystemEventParams = {
    form.bindFromRequest(request.queryString).value.getOrElse(new SystemEventParams())
  }
}