package models

import defines._
import models.base._

import models.base.Persistable
import models.json._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import eu.ehri.project.definitions.Ontology
import backend.rest.Constants
import java.net.URL
import play.api.data.Form
import play.api.data.Forms._
import scala.Some
import play.api.libs.json.JsObject


object DocumentaryUnitF {

  object CopyrightStatus extends Enumeration {
    val Yes = Value("yes")
    val No = Value("no")
    val Unknown = Value("unknown")

    implicit val format = defines.EnumUtils.enumFormat(this)
  }

  object Scope extends Enumeration {
    val High = Value("high")
    val Medium = Value("medium")
    val Low = Value("low")

    implicit val format = defines.EnumUtils.enumFormat(this)
  }

  val OTHER_IDENTIFIERS = "otherIdentifiers"
  val PUBLICATION_STATUS = "publicationStatus"
  final val SCOPE = "scope"
  final val COPYRIGHT = "copyright"

  import models.Entity._
  import eu.ehri.project.definitions.Ontology._

  implicit val documentaryUnitWrites: Writes[DocumentaryUnitF] = new Writes[DocumentaryUnitF] {
    def writes(d: DocumentaryUnitF): JsValue = {
      Json.obj(
        ID -> d.id,
        TYPE -> d.isA,
        DATA -> Json.obj(
          IDENTIFIER -> d.identifier,
          OTHER_IDENTIFIERS -> d.otherIdentifiers,
          PUBLICATION_STATUS -> d.publicationStatus,
          COPYRIGHT -> d.copyrightStatus.orElse(Some(CopyrightStatus.Unknown)),
          SCOPE -> d.scope
        ),
        RELATIONSHIPS -> Json.obj(
          Ontology.DESCRIPTION_FOR_ENTITY -> Json.toJson(d.descriptions.map(Json.toJson(_)).toSeq)
        )
      )
    }
  }

  implicit val documentaryUnitReads: Reads[DocumentaryUnitF] = (
    (__ \ TYPE).read[EntityType.Value](equalsReads(EntityType.DocumentaryUnit)) and
      (__ \ ID).readNullable[String] and
      (__ \ DATA \ IDENTIFIER).read[String] and
      ((__ \ DATA \ OTHER_IDENTIFIERS).readNullable[List[String]] orElse
        (__ \ DATA \ OTHER_IDENTIFIERS).readNullable[String].map(os => os.map(List(_))) ) and
      (__ \ DATA \ PUBLICATION_STATUS).readNullable[PublicationStatus.Value] and
      ((__ \ DATA \ COPYRIGHT).read[Option[CopyrightStatus.Value]] orElse Reads.pure(Some(CopyrightStatus.Unknown))) and
      (__ \ DATA \ SCOPE).readNullable[Scope.Value] and
      (__ \ RELATIONSHIPS \ DESCRIPTION_FOR_ENTITY).lazyReadNullable[List[DocumentaryUnitDescriptionF]](
        Reads.list[DocumentaryUnitDescriptionF]).map(_.getOrElse(List.empty[DocumentaryUnitDescriptionF]))
    )(DocumentaryUnitF.apply _)

  implicit val documentaryUnitFormat: Format[DocumentaryUnitF] = Format(documentaryUnitReads,documentaryUnitWrites)

  implicit object Converter extends RestConvertable[DocumentaryUnitF] with ClientConvertable[DocumentaryUnitF] {
    val restFormat = documentaryUnitFormat

    private implicit val docDescFmt = DocumentaryUnitDescriptionF.Converter.clientFormat
    val clientFormat = Json.format[DocumentaryUnitF]
  }
}

case class DocumentaryUnitF(
  isA: EntityType.Value = EntityType.DocumentaryUnit,
  id: Option[String] = None,
  identifier: String,
  otherIdentifiers: Option[List[String]] = None,
  publicationStatus: Option[PublicationStatus.Value] = None,
  copyrightStatus: Option[DocumentaryUnitF.CopyrightStatus.Value] = Some(DocumentaryUnitF.CopyrightStatus.Unknown),
  scope: Option[DocumentaryUnitF.Scope.Value] = Some(DocumentaryUnitF.Scope.Low),

  @Annotations.Relation(Ontology.DESCRIPTION_FOR_ENTITY)
  descriptions: List[DocumentaryUnitDescriptionF] = Nil
) extends Model
  with Persistable
  with Described[DocumentaryUnitDescriptionF] {
  def withDescription(d: DocumentaryUnitDescriptionF): DocumentaryUnitF = copy(descriptions = descriptions ++ List(d))

  /**
   * Get a description with a given id.
   * @param did
   * @return
   */
  override def description(did: String): Option[DocumentaryUnitDescriptionF] = descriptions.find(d => d.id.isDefined && d.id.get == did)

  /**
   * Replace an existing description with the same id as this one, or add
   * this one to the end of the list of descriptions.
   * @param d
   * @return
   */
  def replaceDescription(d: DocumentaryUnitDescriptionF): DocumentaryUnitF = d.id.map {
    did =>
    // If the description has an id, replace the existing one with that id
      val newDescriptions = descriptions.map {
        dm =>
          if (dm.id.isDefined && dm.id.get == did) d else dm
      }
      copy(descriptions = newDescriptions)
  } getOrElse {
    withDescription(d)
  }
}

object DocumentaryUnit {
  import models.Entity._
  import models.DocumentaryUnitF._
  import eu.ehri.project.definitions.Ontology.{OTHER_IDENTIFIERS => _, _}

  implicit val metaReads: Reads[DocumentaryUnit] = (
    __.read[DocumentaryUnitF](documentaryUnitReads) and
      // Holder
      (__ \ RELATIONSHIPS \ DOC_HELD_BY_REPOSITORY).lazyReadNullable[List[Repository]](
        Reads.list(Repository.Converter.restReads)).map(_.flatMap(_.headOption)) and
      //
      (__ \ RELATIONSHIPS \ DOC_IS_CHILD_OF).lazyReadNullable[List[DocumentaryUnit]](
        Reads.list(metaReads)).map(_.flatMap(_.headOption)) and
      (__ \ RELATIONSHIPS \ IS_ACCESSIBLE_TO).lazyReadNullable[List[Accessor]](
        Reads.list(Accessor.Converter.restReads)).map(_.getOrElse(List.empty[Accessor])) and
      (__ \ RELATIONSHIPS \ ENTITY_HAS_LIFECYCLE_EVENT).lazyReadNullable[List[SystemEvent]](
        Reads.list(SystemEvent.Converter.restReads)).map(_.flatMap(_.headOption)) and
      (__ \ META).readNullable[JsObject].map(_.getOrElse(JsObject(Seq())))
    )(DocumentaryUnit.apply _)


  implicit object Converter extends RestReadable[DocumentaryUnit] with ClientConvertable[DocumentaryUnit] {
    implicit val restReads = metaReads

    val clientFormat: Format[DocumentaryUnit] = (
      __.format[DocumentaryUnitF](DocumentaryUnitF.Converter.clientFormat) and
        (__ \ "holder").formatNullable[Repository](Repository.Converter.clientFormat) and
        (__ \ "parent").lazyFormatNullable[DocumentaryUnit](clientFormat) and
        nullableListFormat(__ \ "accessibleTo")(Accessor.Converter.clientFormat) and
        (__ \ "event").formatNullable[SystemEvent](SystemEvent.Converter.clientFormat) and
        (__ \ "meta").format[JsObject]
      )(DocumentaryUnit.apply _, unlift(DocumentaryUnit.unapply _))
  }

  implicit object Resource extends RestResource[DocumentaryUnit] {
    val entityType = EntityType.DocumentaryUnit

    /**
     * When displaying doc units we need the
     * repositories urlPattern to create an external link. However this
     * is not a mandatory property and thus not returned by the REST
     * interface by default, unless we specify it explicitly.
     */
    override def defaultParams = Seq(
      Constants.INCLUDE_PROPERTIES_PARAM -> RepositoryF.URL_PATTERN,
      Constants.INCLUDE_PROPERTIES_PARAM -> RepositoryF.LOGO_URL
    )
  }

  val form = Form(
    mapping(
      ISA -> ignored(EntityType.DocumentaryUnit),
      ID -> optional(nonEmptyText),
      IDENTIFIER -> nonEmptyText,
      OTHER_IDENTIFIERS -> optional(list(nonEmptyText)),
      PUBLICATION_STATUS -> optional(models.forms.enum(defines.PublicationStatus)),
      COPYRIGHT -> optional(models.forms.enum(CopyrightStatus)),
      SCOPE -> optional(models.forms.enum(Scope)),
      "descriptions" -> list(DocumentaryUnitDescription.form.mapping)
    )(DocumentaryUnitF.apply)(DocumentaryUnitF.unapply)
  )
}

case class DocumentaryUnit(
  model: DocumentaryUnitF,
  holder: Option[Repository] = None,
  parent: Option[DocumentaryUnit] = None,
  accessors: List[Accessor] = Nil,
  latestEvent: Option[SystemEvent] = None,
  meta: JsObject = JsObject(Seq())
) extends AnyModel
  with MetaModel[DocumentaryUnitF]
  with DescribedMeta[DocumentaryUnitDescriptionF, DocumentaryUnitF]
  with Hierarchical[DocumentaryUnit]
  with Holder[DocumentaryUnit]
  with Accessible {

  def url: Option[URL] = (for {
    desc <- descriptions
    url <- desc.externalLink(this)
  } yield new URL(url)).headOption
}
