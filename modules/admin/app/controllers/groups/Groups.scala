package controllers.groups

import play.api.libs.concurrent.Execution.Implicits._
import controllers.generic._
import forms.VisibilityForm
import models.{UserProfile, AccountDAO, Group, GroupF}
import models.base.Accessor
import defines.{EntityType, PermissionType}
import com.google.inject._
import utils.search.{Resolver, Dispatcher}
import scala.concurrent.Future
import backend.Backend
import backend.rest.{Constants, RestHelpers}
import play.api.mvc.{Request, AnyContent}
import play.api.data.{Forms, Form}
import controllers.base.AdminController

case class Groups @Inject()(implicit globalConfig: global.GlobalConfig, searchDispatcher: Dispatcher, searchResolver: Resolver, backend: Backend, userDAO: AccountDAO) extends AdminController
  with PermissionHolder[Group]
  with Visibility[Group]
  with CRUD[GroupF, Group] {

  private val form = models.Group.form
  private val groupRoutes = controllers.groups.routes.Groups

  def get(id: String) = getWithChildrenAction[Accessor](id) {
      item => page => params => annotations => links => implicit maybeUser => implicit request =>
    val pageWithAccounts = page.copy(items = page.items.map {
      case up: UserProfile => up.copy(account = userDAO.findByProfileId(up.id))
      case group => group
    })
    Ok(views.html.admin.group.show(item, pageWithAccounts, params, annotations))
  }

  def history(id: String) = ItemHistoryAction(id).apply { implicit request =>
    Ok(views.html.admin.systemEvents.itemList(request.item, request.page, request.params))
  }

  def list = ItemPageAction.apply { implicit request =>
    Ok(views.html.admin.group.list(request.page, request.params))
  }

  def create = NewItemAction.apply { implicit request =>
    Ok(views.html.admin.group.create(form, VisibilityForm.form,
      request.users, request.groups, groupRoutes.createPost()))
  }

  /**
   * Extract a set of group members from the form POST data and
   * convert it into params for the backend call.
   */
  private def memberExtractor: Request[_] => Map[String,Seq[String]] = { implicit r =>
    Map(Constants.MEMBER -> Form(Forms.single(
      Constants.MEMBER -> Forms.seq(Forms.nonEmptyText)
    )).bindFromRequest().value.getOrElse(Seq.empty))
  }

  def createPost = CreateItemAction(form, memberExtractor).async { implicit request =>
    request.formOrItem match {
      case Left((errorForm,accForm)) => getUsersAndGroups { users => groups =>
        BadRequest(views.html.admin.group.create(
          errorForm, accForm, users, groups, groupRoutes.createPost()))
      }
      case Right(item) => Future.successful(Redirect(groupRoutes.get(item.id))
        .flashing("success" -> "item.create.confirmation"))
    }
  }

  def update(id: String) = EditAction(id).apply { implicit request =>
    Ok(views.html.admin.group.edit(
        request.item, form.fill(request.item.model), groupRoutes.updatePost(id)))
  }

  def updatePost(id: String) = UpdateAction(id, form).apply { implicit request =>
    request.formOrItem match {
      case Left(errorForm) =>
        BadRequest(views.html.admin.group.edit(
          request.item, errorForm, groupRoutes.updatePost(id)))
      case Right(updated) => Redirect(groupRoutes.get(updated.id))
        .flashing("success" -> "item.update.confirmation")
    }
  }

  def delete(id: String) = CheckDeleteAction(id).apply { implicit request =>
    Ok(views.html.admin.delete(
      request.item, groupRoutes.deletePost(id), groupRoutes.get(id)))
  }

  def deletePost(id: String) = DeleteAction(id).apply { implicit request =>
    Redirect(groupRoutes.list())
        .flashing("success" -> "item.delete.confirmation")
  }

  def grantList(id: String) = GrantListAction(id).apply { implicit request =>
    Ok(views.html.admin.permissions.permissionGrantList(request.item, request.permissionGrants))
  }

  def permissions(id: String) = CheckGlobalPermissionsAction(id).apply { implicit request =>
    Ok(views.html.admin.permissions.editGlobalPermissions(request.item, request.permissions,
          groupRoutes.permissionsPost(id)))
  }

  def permissionsPost(id: String) = SetGlobalPermissionsAction(id).apply { implicit request =>
    Redirect(groupRoutes.get(id))
        .flashing("success" -> "item.update.confirmation")
  }


  def revokePermission(id: String, permId: String) = {
    CheckRevokePermissionAction(id, permId).apply { implicit request =>
      Ok(views.html.admin.permissions.revokePermission(
        request.item, request.permissionGrant,
        groupRoutes.revokePermissionPost(id, permId), groupRoutes.grantList(id)))
    }
  }

  def revokePermissionPost(id: String, permId: String) = {
    RevokePermissionAction(id, permId).apply { implicit request =>
      Redirect(groupRoutes.grantList(id))
        .flashing("success" -> "item.delete.confirmation")
    }
  }

  /*
   *	Membership
   */

  /**
   * Present a list of groups to which the current user can be added.
   */
  def membership(userType: EntityType.Value, userId: String) = {
    implicit val resource = Accessor.resourceFor(userType)
    withItemPermission.async[Accessor](userId, PermissionType.Grant) {
        item => implicit userOpt => implicit request =>
      for {
        groups <- RestHelpers.getGroupList
      } yield {
        // filter out the groups the user already belongs to
        val filteredGroups = groups.filter(t => t._1 != item.id).filter {
          case (ident, name) =>
            // if the user is admin, they can add the user to ANY group
            if (userOpt.exists(_.isAdmin)) {
              !item.groups.map(_.id).contains(ident)
            } else {
              // if not, they can add the user to groups they belong to
              // TODO: Enforce these policies with the permission system!
              // TODO: WRITE TESTS FOR THESE WEIRD BEHAVIOURS!!!
              (!item.groups.map(_.id).contains(ident)) &&
                userOpt.exists(_.groups.map(_.id).contains(ident))
            }
        }
        Ok(views.html.admin.group.membership(item, filteredGroups))
      }
    }
  }

  /**
   * Confirm adding the given user to the specified group.
   */
  def addMember(id: String, userType: EntityType.Value, userId: String) = {
    implicit val resource = Accessor.resourceFor(userType)
    withItemPermission.async[Accessor](userId, PermissionType.Grant) {
        item => implicit userOpt => implicit request =>
      backend.get[Group](id).map { group =>
        Ok(views.html.admin.group.confirmMembership(group, item,
          groupRoutes.addMemberPost(id, userType, userId)))
      }
    }
  }

  /**
   * Add the user to the group and redirect to the show view.
   */
  def addMemberPost(id: String, userType: EntityType.Value, userId: String) = {
    implicit val resource = Accessor.resourceFor(userType)
    withItemPermission.async[Accessor](userId, PermissionType.Grant) {
        item => implicit userOpt => implicit request =>
      backend.addGroup(id, userId).map { ok =>
        Redirect(groupRoutes.membership(userType, userId))
          .flashing("success" -> "item.update.confirmation")
      }
    }
  }

  /**
   * Confirm adding the given user to the specified group.
   */
  def removeMember(id: String, userType: EntityType.Value, userId: String) = {
    implicit val resource = Accessor.resourceFor(userType)
    withItemPermission.async[Accessor](userId, PermissionType.Grant) {
        item => implicit userOpt => implicit request =>
      backend.get[Group](id).map { group =>
        Ok(views.html.admin.group.removeMembership(group, item,
          groupRoutes.removeMemberPost(id, userType, userId)))
      }
    }
  }

  /**
   * Add the user to the group and redirect to the show view.
   */
  def removeMemberPost(id: String, userType: EntityType.Value, userId: String) = {
    implicit val resource = Accessor.resourceFor(userType)
    withItemPermission.async[Accessor](userId, PermissionType.Grant) {
        item => implicit userOpt => implicit request =>
      backend.removeGroup(id, userId).map { ok =>
        Redirect(groupRoutes.membership(userType, userId))
          .flashing("success" -> "item.update.confirmation")
      }
    }
  }
}
