package controllers.portal

import play.api.libs.concurrent.Execution.Implicits._
import defines.EntityType
import utils.{ListParams, PageParams}
import models.{Link, Annotation, UserProfile}
import play.api.mvc._
import models.json.{RestResource, ClientConvertable, RestReadable}
import controllers.base.{ControllerHelpers, AuthController}
import scala.concurrent.Future
import play.api.mvc.SimpleResult
import backend.Page

case class ItemDetails(
  annotations: Map[String,List[Annotation]],
  links: List[Link],
  watched: Boolean
)

/**
 * @author Mike Bryant (http://github.com/mikesname)
 */
trait PortalActions {

  self: AuthController with ControllerHelpers =>

  def pageAction[MT](entityType: EntityType.Value, paramsOpt: Option[PageParams] = None)(f: Page[MT] => PageParams => Option[UserProfile] => Request[AnyContent] => SimpleResult)(
    implicit rs: RestResource[MT], rd: RestReadable[MT]) = {
    userProfileAction.async { implicit userOpt => implicit request =>
      val params = paramsOpt.getOrElse(PageParams.fromRequest(request))
      backend.page(params).map { page =>
        f(page)(params)(userOpt)(request)
      }
    }
  }

  def listAction[MT](entityType: EntityType.Value, paramsOpt: Option[ListParams] = None)(f: List[MT] => ListParams => Option[UserProfile] => Request[AnyContent] => SimpleResult)(
    implicit rs: RestResource[MT], rd: RestReadable[MT]) = {
    userProfileAction.async { implicit userOpt => implicit request =>
      val params = paramsOpt.getOrElse(ListParams.fromRequest(request))
      backend.list(params).map { list =>
        f(list)(params)(userOpt)(request)
      }
    }
  }

  /**
   * Fetch a given item, along with its links and annotations.
   */
  object getAction {
    def async[MT](entityType: EntityType.Value, id: String)(
      f: MT => ItemDetails => Option[UserProfile] => Request[AnyContent] => Future[SimpleResult])(
                   implicit rs: RestResource[MT], rd: RestReadable[MT], cfmt: ClientConvertable[MT]): Action[AnyContent] = {
      itemAction.async[MT](entityType, id) { item => implicit userOpt => implicit request =>

        def isWatching =
          if (userOpt.isDefined)backend.isWatching(userOpt.get.id, id)
          else Future.successful(false)

        for {
          watching <- isWatching
          anns <- backend.getAnnotationsForItem(id)
          links <- backend.getLinksForItem(id)
          r <- f(item)(ItemDetails(anns, links, watching))(userOpt)(request)
        } yield r
      }
    }

    def apply[MT](entityType: EntityType.Value, id: String)(
      f: MT => ItemDetails => Option[UserProfile] => Request[AnyContent] => SimpleResult)(
                   implicit rs: RestResource[MT], rd: RestReadable[MT], cfmt: ClientConvertable[MT]) = {
      async(entityType, id)(f.andThen(_.andThen(_.andThen(_.andThen(t => Future.successful(t))))))
    }
  }

  /**
   * Fetch a given item with links, annotations, and a page of child items.
   */
  object getWithChildrenAction {
    def async[CT, MT](entityType: EntityType.Value, id: String)(
      f: MT => Page[CT] => PageParams =>  ItemDetails => Option[UserProfile] => Request[AnyContent] => Future[SimpleResult])(
                       implicit rs: RestResource[MT], rd: RestReadable[MT], crd: RestReadable[CT], cfmt: ClientConvertable[MT]): Action[AnyContent] = {
      getAction.async[MT](entityType, id) { item => details => implicit userOpt => implicit request =>
        val params = PageParams.fromRequest(request)
        backend.pageChildren[MT,CT](id, params).flatMap { children =>
          f(item)(children)(params)(details)(userOpt)(request)
        }
      }
    }

    def apply[CT, MT](entityType: EntityType.Value, id: String)(
      f: MT => Page[CT] => PageParams =>  ItemDetails => Option[UserProfile] => Request[AnyContent] => SimpleResult)(
                       implicit rs: RestResource[MT], rd: RestReadable[MT], crd: RestReadable[CT], cfmt: ClientConvertable[MT]): Action[AnyContent] = {
      async(entityType, id)(f.andThen(_.andThen(_.andThen(_.andThen(_.andThen(_.andThen(t => Future.successful(t))))))))
    }
  }
}
