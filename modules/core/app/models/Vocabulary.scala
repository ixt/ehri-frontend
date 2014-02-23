package models

import base._

import models.base.Persistable
import defines.EntityType
import play.api.libs.json._
import models.json._
import play.api.i18n.Lang
import play.api.libs.functional.syntax._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.JsObject

object VocabularyType extends Enumeration {
  type Type = Value
}

object VocabularyF {
  val NAME = "name"
  val DESCRIPTION = "description"

  lazy implicit val vocabularyFormat: Format[VocabularyF] = json.VocabularyFormat.restFormat

  implicit object Converter extends RestConvertable[VocabularyF] with ClientConvertable[VocabularyF] {
    lazy val restFormat = models.json.VocabularyFormat.restFormat
    lazy val clientFormat = Json.format[VocabularyF]
  }
}


case class VocabularyF(
  isA: EntityType.Value = EntityType.Vocabulary,
  id: Option[String],
  identifier: String,
  name: Option[String],
  description: Option[String]
) extends Model with Persistable


object Vocabulary {
  implicit object Converter extends ClientConvertable[Vocabulary] with RestReadable[Vocabulary] {
    val restReads = models.json.VocabularyFormat.metaReads

    val clientFormat: Format[Vocabulary] = (
      __.format[VocabularyF](VocabularyF.Converter.clientFormat) and
        nullableListFormat(__ \ "accessibleTo")(Accessor.Converter.clientFormat) and
        (__ \ "event").formatNullable[SystemEvent](SystemEvent.Converter.clientFormat) and
        (__ \ "meta").format[JsObject]
      )(Vocabulary.apply _, unlift(Vocabulary.unapply _))
  }

  implicit object Resource extends RestResource[Vocabulary] {
    val entityType = EntityType.Vocabulary
  }

  import VocabularyF._

  val form = Form(
    mapping(
      Entity.ISA -> ignored(EntityType.Vocabulary),
      Entity.ID -> optional(nonEmptyText),
      Entity.IDENTIFIER -> nonEmptyText(minLength=3),
      NAME -> optional(nonEmptyText),
      DESCRIPTION -> optional(nonEmptyText)
    )(VocabularyF.apply)(VocabularyF.unapply)
  )
}


case class Vocabulary(
  model: VocabularyF,
  accessors: List[Accessor] = Nil,
  latestEvent: Option[SystemEvent],
  meta: JsObject = JsObject(Seq())
) extends AnyModel
  with MetaModel[VocabularyF]
  with Accessible
  with Holder[Concept] {

  override def toStringLang(implicit lang: Lang): String = model.name.getOrElse(id)
}