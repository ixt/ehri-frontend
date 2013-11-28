package controllers.generic

import play.api.mvc._
import play.api.libs.concurrent.Execution.Implicits._
import defines._
import models._
import play.api.data.Form
import models.forms.AnnotationForm
import play.api.libs.json.{Writes, Format, Json, JsError}
import models.json.RestReadable
import scala.concurrent.Future.{successful => immediate}


object Annotate {
  // Create a format for client read/writes
  implicit val annotationTypeFormat = defines.EnumUtils.enumFormat(AnnotationF.AnnotationType)
  implicit val clientAnnotationFormat: Format[AnnotationF] = Json.format[AnnotationF]
}

/**
 * Trait for setting visibility on any AccessibleEntity.
 *
 * @tparam MT the entity's build class
 */
trait Annotate[MT] extends Read[MT] {

  import Annotate._

  def annotationAction(id: String)(f: MT => Form[AnnotationF] => Option[UserProfile] => Request[AnyContent] => SimpleResult)(implicit rd: RestReadable[MT]): Action[AnyContent] = {
    withItemPermission[MT](id, PermissionType.Annotate, contentType) { item => implicit userOpt => implicit request =>
      f(item)(AnnotationForm.form.bindFromRequest)(userOpt)(request)
    }
  }

  def annotationPostAction(id: String)(f: Either[Form[AnnotationF],Annotation] => Option[UserProfile] => Request[AnyContent] => SimpleResult)(implicit rd: RestReadable[MT]) = {
    withItemPermission.async[MT](id, PermissionType.Annotate, contentType) { item => implicit userOpt => implicit request =>
      AnnotationForm.form.bindFromRequest.fold(
        errorForm => immediate(f(Left(errorForm))(userOpt)(request)),
        ann => backend.createAnnotation(id, ann).map { ann =>
          f(Right(ann))(userOpt)(request)
        }
      )
    }
  }

  /**
   * Fetch annotations for a given item.
   */
  def getAnnotationsAction(id: String)(
      f: Seq[Annotation] => Option[UserProfile] => Request[AnyContent] => SimpleResult) = {
    userProfileAction.async { implicit  userOpt => implicit request =>
      backend.getAnnotationsForItem(id).map { anns =>
        f(anns)(userOpt)(request)
      }
    }
  }

  //
  // JSON endpoints
  //

  def getAnnotationJson(id: String) = getAnnotationsAction(id) {
      anns => implicit userOpt => implicit request =>
    Ok(Json.toJson(anns)(Writes.seq(Annotation.Converter.clientFormat)))
  }

  /**
   * Create an annotation via Ajax...
   *
   * @param id The item's id
   * @return
   */
  def createAnnotationJsonPost(id: String) = Action.async(parse.json) { request =>
    request.body.validate[AnnotationF](clientAnnotationFormat).fold(
      errors => immediate(BadRequest(JsError.toFlatJson(errors))),
      ap => {
        // NB: No checking of permissions here - we're going to depend
        // on the server for that
        userProfileAction.async { implicit userOpt => implicit request =>
          backend.createAnnotation(id, ap).map { ann =>
            Created(Json.toJson(ann.model)(clientAnnotationFormat))
          }
        }(request.map(js => AnyContentAsEmpty))
      }
    )
  }
}
