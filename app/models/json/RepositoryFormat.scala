package models.json

import play.api.libs.functional.syntax._
import play.api.libs.json._


import defines.{EntityType, PublicationStatus}
import models.base.{Accessor, AccessibleEntity, DescribedEntity}
import models._
import defines.EnumUtils._


object RepositoryFormat {
  import models.json.IsdiahFormat._
  import models.Entity._
  import models.RepositoryF._

  implicit val publicationStatusReads = defines.EnumUtils.enumReads(PublicationStatus)

  implicit val repositoryWrites: Writes[RepositoryF] = new Writes[RepositoryF] {
    def writes(d: RepositoryF): JsValue = {
      Json.obj(
        ID -> d.id,
        TYPE -> d.isA,
        DATA -> Json.obj(
          IDENTIFIER -> d.identifier,
          PUBLICATION_STATUS -> d.publicationStatus,
          PRIORITY -> d.priority
        ),
        RELATIONSHIPS -> Json.obj(
          DESC_REL -> Json.toJson(d.descriptions.map(Json.toJson(_)).toSeq)
        )
      )
    }
  }

  implicit val repositoryReads: Reads[RepositoryF] = (
    (__ \ TYPE).read[EntityType.Value](equalsReads(EntityType.Repository)) and
    (__ \ ID).readNullable[String] and
      (__ \ DATA \ IDENTIFIER).read[String] and
      (__ \ DATA \ PUBLICATION_STATUS).readNullable[PublicationStatus.Value] and
      // FIXME: This throws an error if an item has no descriptions - we should somehow
      // make it so that the path being missing is permissable but a validation error
      // is not.
      (__ \ RELATIONSHIPS \ DescribedEntity.DESCRIBES_REL).lazyRead[List[RepositoryDescriptionF]](
        Reads.list[RepositoryDescriptionF]) and
      (__ \ DATA \ PRIORITY).readNullable[Int]
    )(RepositoryF.apply _)

  implicit val restFormat: Format[RepositoryF] = Format(repositoryReads,repositoryWrites)

  private implicit val systemEventReads = SystemEventFormat.metaReads
  private implicit val countryReads = CountryFormat.metaReads

  implicit val metaReads: Reads[RepositoryMeta] = (
    __.read[RepositoryF] and
    // Country
    (__ \ RELATIONSHIPS \ RepositoryF.COUNTRY_REL).lazyReadNullable[List[CountryMeta]](
      Reads.list[CountryMeta]).map(_.flatMap(_.headOption)) and
    (__ \ RELATIONSHIPS \ AccessibleEntity.ACCESS_REL).lazyReadNullable[List[Accessor]](
        Reads.list[Accessor]).map(_.getOrElse(List.empty[Accessor])) and
    (__ \ RELATIONSHIPS \ AccessibleEntity.EVENT_REL).lazyReadNullable[List[SystemEventMeta]](
        Reads.list[SystemEventMeta]).map(_.flatMap(_.headOption))
    )(RepositoryMeta.apply _)

}