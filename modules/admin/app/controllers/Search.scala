package controllers.admin

import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc._
import controllers.base.EntitySearch
import play.Play.application
import play.api.libs.iteratee.{Concurrent, Enumerator}
import models.IsadG
import concurrent.Future
import play.api.i18n.Messages
import views.Helpers
import play.api.libs.json.{Writes, Json}

import com.google.inject._
import solr.facet.FieldFacetClass
import scala.Some
import models.base.AnyModel
import utils.search.{Dispatcher, Indexer, SearchParams, SearchOrder}
import scala.util.{Failure, Success}
import indexing.CmdlineIndexer
import play.api.Logger


object Search {
  /**
   * Message that terminates a long-lived streaming response, such
   * as the search index update job.
   */
  val DONE_MESSAGE = "Done"
  val ERR_MESSAGE = "Index Error"
}

@Singleton
class Search @Inject()(implicit val globalConfig: global.GlobalConfig, val searchDispatcher: Dispatcher, val searchIndexer: Indexer) extends EntitySearch {

  val searchEntities = List()
  // i.e. Everything
  private val entityFacets = List(
    FieldFacetClass(
      key = IsadG.LANG_CODE,
      name = Messages(IsadG.FIELD_PREFIX + "." + IsadG.LANG_CODE),
      param = "lang",
      render = Helpers.languageCodeToName
    ),
    FieldFacetClass(
      key = "type",
      name = Messages("search.type"),
      param = "type",
      render = s => Messages("contentTypes." + s)
    ),
    FieldFacetClass(
      key = "copyrightStatus",
      name = Messages("copyrightStatus.copyright"),
      param = "copyright",
      render = s => Messages("copyrightStatus." + s)
    ),
    FieldFacetClass(
      key = "scope",
      name = Messages("scope.scope"),
      param = "scope",
      render = s => Messages("scope." + s)
    )
  )

  /**
   * Full text search action that returns a complete page of item data.
   * @return
   */
  private implicit val anyModelReads = AnyModel.Converter.restReads

  def search = searchAction[AnyModel](
    defaultParams = Some(SearchParams(sort = Some(SearchOrder.Score))),
    entityFacets = entityFacets) {
    page => params => facets => implicit userOpt => implicit request =>
      Secured {
        render {
          case Accepts.Json() => {
            Ok(Json.toJson(Json.obj(
              "numPages" -> page.numPages,
              "page" -> Json.toJson(page.items.map(_._1))(Writes.seq(AnyModel.Converter.clientFormat)),
              "facets" -> facets
            ))
            )
          }
          case _ => Ok(views.html.search.search(page, params, facets,
            controllers.admin.routes.Search.search))
        }
      }
  }

  /**
   * Quick filter action that searches applies a 'q' string filter to
   * only the name_ngram field and returns an id/name pair.
   * @return
   */
  def filter = filterAction() {
    page => implicit userOpt => implicit request =>
      Ok(Json.obj(
        "numPages" -> page.numPages,
        "page" -> page.page,
        "items" -> page.items.map {
          case (id, name, t) =>
            Json.arr(id, name, t.toString)
        }
      ))
  }


  import play.api.data.Form
  import play.api.data.Forms._
  import models.forms.enum

  private lazy val defaultBatchSize: Int
  = application.configuration.getInt("solr.update.batchSize")

  private val updateIndexForm = Form(
    tuple(
      "all" -> default(boolean, false),
      "type" -> list(enum(defines.EntityType))
    )
  )

  /**
   * Render the update form
   * @return
   */
  def updateIndex = adminAction {
    implicit userOpt => implicit request =>
      Ok(views.html.search.updateIndex(form = updateIndexForm,
        action = controllers.admin.routes.Search.updateIndexPost))
  }

  /**
   * Perform the actual update, returning a streaming response as the batch
   * jobs complete.
   *
   * FIXME: In order to comprehend the flow of this stuff error handling has
   * been thrown out the window, but we should fix this at some point.
   *
   * @return
   */
  def updateIndexPost = adminAction { implicit userOpt => implicit request =>

    val (deleteAll, entities) = updateIndexForm.bindFromRequest.value.get

    def wrapMsg(m: String) = s"<message>$m</message>"

    // Create an unicast channel in which to feed progress messages
    val channel = Concurrent.unicast[String] { chan =>

      def optionallyClearIndex: Future[Unit] = {
        if (!deleteAll) Future.successful(Unit)
        else {
          val f = searchIndexer.clearAll()
          f.onSuccess {
            case () => chan.push(wrapMsg("... finished clearing index"))
          }
          f
        }
      }

      val job = optionallyClearIndex.flatMap { _ =>
        searchIndexer.withChannel(chan, wrapMsg).indexTypes(entityTypes = entities)
      }

      job.onComplete {
        case Success(()) => {
          chan.push(wrapMsg(Search.DONE_MESSAGE))
          chan.eofAndEnd()
        }
        case Failure(t) => {
          Logger.logger.error(t.getMessage)
          chan.push(wrapMsg("Indexing operation failed: " + t.getMessage))
          chan.push(wrapMsg(Search.ERR_MESSAGE))
          chan.eofAndEnd()
        }
      }
    }

    Ok.stream(channel.andThen(Enumerator.eof))
  }
}
