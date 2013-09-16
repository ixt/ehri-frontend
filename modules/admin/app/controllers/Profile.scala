package controllers.admin

import _root_.controllers.base.{EntityUpdate, EntitySearch}
import _root_.models.{UserProfileF, UserProfile, Isaar, IsadG}
import models.base.AnyModel

import play.api._
import play.api.mvc._
import defines.{ContentTypes, EntityType}
import play.api.i18n.Messages
import views.Helpers
import play.api.libs.json.Json
import utils.search._
import solr.facet.FieldFacetClass

import com.google.inject._
import play.api.http.MimeTypes


/**
 * Controller for a user managing their own profile.
 * @param globalConfig
 */
@Singleton
class Profile @Inject()(implicit val globalConfig: global.GlobalConfig) extends EntityUpdate[UserProfileF,UserProfile] {

  val entityType = EntityType.UserProfile
  val contentType = ContentTypes.UserProfile
  val form = models.forms.UserProfileForm.form

  /**
   * Render a user's profile.
   * @return
   */
  def profile = userProfileAction { implicit userOpt => implicit request =>
    Secured {
      userOpt.map { user =>
        Ok(views.html.profile(user))
      } getOrElse {
        authenticationFailed(request)
      }
    }
  }

  def updateProfile = userProfileAction { implicit userOpt => implicit request =>
    userOpt.map { user =>
      Ok(views.html.userProfile.edit(
        user, form.fill(user.model), controllers.admin.routes.Profile.updateProfilePost))
    } getOrElse {
      Unauthorized
    }
  }

  def updateProfilePost = userProfileAction { implicit userOpt => implicit request =>
    userOpt.map { user =>

      // This action is more-or-less the same as in UserProfiles update, except
      // we don't allow the user to update their own identifier.
      val transform: UserProfileF => UserProfileF = { newDetails =>
        newDetails.copy(identifier = user.model.identifier)
      }

      updateAction(user, form, transform) { item => formOrItem => up => r =>
        formOrItem match {
          case Left(errorForm) =>
            BadRequest(views.html.userProfile.edit(
              user, errorForm, controllers.admin.routes.Profile.updateProfilePost))
          case Right(item) => Redirect(controllers.admin.routes.Profile.profile)
            .flashing("success" -> Messages("confirmations.profileUpdated"))
        }
      }
    } getOrElse {
      Unauthorized
    }
  }

}