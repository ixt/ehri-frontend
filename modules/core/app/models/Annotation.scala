package models

import models.base._
import defines.{ContentTypes, EntityType}
import models.json._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import eu.ehri.project.definitions.Ontology
import play.api.data.Form
import play.api.data.Forms._
import defines.EnumUtils._
import backend._
import play.api.libs.json.JsObject


object AnnotationF {
  val BODY = "body"
  val FIELD = "field"
  val ANNOTATION_TYPE = "annotationType"
  val COMMENT = "comment"
  val IS_PRIVATE = "isPrivate"

  object AnnotationType extends Enumeration {
    type Type = Value
    val Comment = Value("comment")
    val Aggregation = Value("aggregation")

    implicit val _format = defines.EnumUtils.enumFormat(this)
  }

  import AnnotationF.{ANNOTATION_TYPE => ANNOTATION_TYPE_PROP}
  import Entity._
  import Ontology._

  implicit val annotationTypeReads = enumReads(AnnotationType)

  implicit val annotationWrites: Writes[AnnotationF] = new Writes[AnnotationF] {
    def writes(d: AnnotationF): JsValue = {
      Json.obj(
        ID -> d.id,
        TYPE -> d.isA,
        DATA -> Json.obj(
          ANNOTATION_TYPE_PROP -> d.annotationType,
          BODY -> d.body,
          FIELD -> d.field,
          COMMENT -> d.comment,
          IS_PROMOTABLE -> d.isPromotable
        )
      )
    }
  }

  implicit val annotationReads: Reads[AnnotationF] = (
    (__ \ TYPE).readIfEquals(EntityType.Annotation) and
    (__ \ ID).readNullable[String] and
    (__ \ DATA \ ANNOTATION_TYPE_PROP).readWithDefault(Option(AnnotationType.Comment)) and
    (__ \ DATA \ BODY).read[String] and
    (__ \ DATA \ FIELD).readNullable[String] and
    (__ \ DATA \ COMMENT).readNullable[String] and
    (__ \ DATA \ IS_PROMOTABLE).readNullable[Boolean].map(_.getOrElse(false))
  )(AnnotationF.apply _)

  implicit object Converter extends BackendWriteable[AnnotationF] {
    lazy val restFormat = Format(annotationReads,annotationWrites)
    lazy val clientFormat = Json.format[AnnotationF]
  }
}

case class AnnotationF(
  isA: EntityType.Value = EntityType.Annotation,
  id: Option[String],
  annotationType: Option[AnnotationF.AnnotationType.Type] = Some(AnnotationF.AnnotationType.Comment),
  body: String,
  field: Option[String] = None,
  comment: Option[String] = None,
  isPromotable: Boolean = false
) extends Model with Persistable


object Annotation {
  import Entity._
  import Ontology._

  private implicit val anyModelReads = AnyModel.Converter.restReads
  private implicit val userProfileMetaReads = UserProfile.Resource.restReads
  private lazy implicit val systemEventReads = SystemEvent.Resource.restReads
  private implicit val accessorReads = Accessor.Converter.restReads

  implicit val metaReads: Reads[Annotation] = (
    __.read[AnnotationF] and
    (__ \ RELATIONSHIPS \ ANNOTATION_ANNOTATES).lazyNullableListReads[Annotation](metaReads) and
    (__ \ RELATIONSHIPS \ ANNOTATOR_HAS_ANNOTATION).nullableHeadReads[UserProfile] and
    (__ \ RELATIONSHIPS \ ANNOTATION_HAS_SOURCE).lazyNullableHeadReads[AnyModel](anyModelReads) and
    (__ \ RELATIONSHIPS \ ANNOTATES).lazyNullableHeadReads[AnyModel](anyModelReads) and
    (__ \ RELATIONSHIPS \ ANNOTATES_PART).nullableHeadReads[Entity] and
    (__ \ RELATIONSHIPS \ IS_ACCESSIBLE_TO).nullableListReads[Accessor] and
    (__ \ RELATIONSHIPS \ PROMOTED_BY).nullableListReads[UserProfile] and
    (__ \ RELATIONSHIPS \ DEMOTED_BY).nullableListReads[UserProfile] and
    (__ \ RELATIONSHIPS \ ENTITY_HAS_LIFECYCLE_EVENT).nullableHeadReads[SystemEvent] and
    (__ \ META).readWithDefault(Json.obj())
  )(Annotation.apply _)

  implicit object Resource extends BackendContentType[Annotation] {
    val entityType = EntityType.Annotation
    val contentType = ContentTypes.Annotation
    val restReads = metaReads
  }

  /**
   * Filter annotations on individual fields
   */
  def fieldAnnotations(partId: Option[String], annotations: Seq[Annotation]): Seq[Annotation] =
    annotations.filter(_.targetParts.exists(p => Some(p.id) == partId)).filter(_.model.field.isDefined)

  /**
   * Filter annotations on the item
   */
  def itemAnnotations(partId: Option[String], annotations: Seq[Annotation]): Seq[Annotation] =
    annotations.filter(_.targetParts.exists(p => Some(p.id) == partId))

  /**
   * Filter annotations on the item
   */
  def itemAnnotations(annotations: Seq[Annotation]): Seq[Annotation] =
      annotations.filter(_.targetParts.isEmpty).filter(_.model.field.isDefined)

  import AnnotationF.{ANNOTATION_TYPE => ANNOTATION_TYPE_PROP, _}

  val form = Form(mapping(
    ISA -> ignored(EntityType.Annotation),
    ID -> optional(nonEmptyText),
    ANNOTATION_TYPE_PROP -> optional(utils.forms.enum(AnnotationType)),
    BODY -> nonEmptyText(maxLength = 600),
    FIELD -> optional(nonEmptyText),
    COMMENT -> optional(nonEmptyText),
    // NB: The object itself has an isPromotable flag, but the
    // form uses reverse semantics, forcing the user to un-check
    // the isPrivate (default: true) flag.
    IS_PRIVATE -> default(boolean.transform[Boolean](f => !f, f => !f), true)
  )(AnnotationF.apply)(AnnotationF.unapply))

  val multiForm = Form(single(
    "annotation" -> list(tuple(
      "id" -> nonEmptyText,
      "data" -> form.mapping
    ))
  ))
}

case class Annotation(
  model: AnnotationF,
  annotations: List[Annotation] = Nil,
  user: Option[UserProfile] = None,
  source: Option[AnyModel] = None,
  target: Option[AnyModel] = None,
  targetParts: Option[Entity] = None,
  accessors: List[Accessor] = Nil,
  promoters: List[UserProfile] = Nil,
  demoters: List[UserProfile] = Nil,
  latestEvent: Option[SystemEvent] = None,
  meta: JsObject = JsObject(Seq())
) extends MetaModel[AnnotationF] with Accessible with Promotable {

  def isPromotable: Boolean = model.isPromotable
  def isOwnedBy(userOpt: Option[UserProfile]): Boolean = {
    (for {
      u <- userOpt
      creator <-user
    } yield u.id == creator.id).getOrElse(false)
  }

  def formatted: String = {
    "%s%s".format(
      model.comment.map(c => s"$c\n\n").getOrElse(""),
      model.body
    )
  }
}