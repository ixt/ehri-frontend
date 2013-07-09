//
// Global request object
//

import defines.EntityType
import play.api._
import play.api.libs.json.{Format, Reads}
import play.api.mvc._

import org.apache.commons.codec.binary.Base64

import play.api.Play.current
import play.filters.csrf.CSRFFilter
import rest.EntityDAO
import scala.Some
import solr.SolrIndexer.SolrErrorResponse

/**
 * Filter that applies CSRF protection unless a particular
 * custom header is present. The value of the header is
 * not checked.
 */
class AjaxCSRFFilter extends EssentialFilter {
  var csrfFilter = new CSRFFilter()

  val AJAX_HEADER_TOKEN = "ajax-ignore-csrf"

  def apply(next: EssentialAction) = new EssentialAction {
    def apply(request: RequestHeader) = {
      if (request.headers.keys.contains(AJAX_HEADER_TOKEN))
        next(request)
      else
        csrfFilter(next)(request)
    }
  }
}


// Note: this is in the default package.
object Global extends WithFilters(new AjaxCSRFFilter()) with GlobalSettings {
    
  override def onStart(app: Application) {

    // Register JSON models!
    models.json.Utils.registerModels

    // Register menu parts
    core.views.MenuConfig.mainSection.put(
      "pages.search", controllers.routes.Search.search.url)
    core.views.MenuConfig.mainSection.put(
      "contentTypes.documentaryUnit", controllers.routes.DocumentaryUnits.search.url)
    core.views.MenuConfig.mainSection.put(
      "contentTypes.historicalAgent", controllers.routes.HistoricalAgents.search.url)
    core.views.MenuConfig.mainSection.put(
      "contentTypes.repository", controllers.routes.Repositories.search.url)
    core.views.MenuConfig.mainSection.put(
      "contentTypes.cvocConcept", controllers.routes.Concepts.search.url)

    core.views.MenuConfig.adminSection.put(
      "contentTypes.userProfile", controllers.routes.UserProfiles.search.url)
    core.views.MenuConfig.adminSection.put(
      "contentTypes.group", controllers.routes.Groups.list.url)
    core.views.MenuConfig.adminSection.put(
      "contentTypes.country", controllers.routes.Countries.search.url)
    core.views.MenuConfig.adminSection.put(
      "contentTypes.cvocVocabulary", controllers.routes.Vocabularies.list.url)
    core.views.MenuConfig.adminSection.put(
      "contentTypes.authoritativeSet", controllers.routes.AuthoritativeSets.list.url)

    import play.api.libs.concurrent.Execution.Implicits._

    // Bind the EntityDAO Create/Update/Delete actions
    // to the SolrIndexer update/delete handlers
    EntityDAO.addCreateHandler { item =>
      Logger.logger.info("Binding creation event to Solr create action")
      /*solr.SolrIndexer.updateItems(Stream(item)).map { batchList =>
        batchList.map { r => r match {
            case e: SolrErrorResponse => Logger.logger.error("Solr update error: " + e.err)
            case ok => ok
          }
        }
      }*/
    }

    EntityDAO.addUpdateHandler { item =>
      Logger.logger.info("Binding update event to Solr update action")
      /*solr.SolrIndexer.updateItems(Stream(item)).map { batchList =>
        batchList.map { r => r match {
            case e: SolrErrorResponse => Logger.logger.error("Solr update error: " + e.err)
            case ok => ok
          }
        }
      }*/
    }

    EntityDAO.addDeleteHandler { item =>
      Logger.logger.info("Binding delete event to Solr delete action")
      solr.SolrIndexer.deleteItemsById(Stream(item)).map { r => r match {
          case e: SolrErrorResponse => Logger.logger.error("Solr update error: " + e.err)
          case ok => ok
        }
      }
    }
  }

  private def noAuthAction = Action { request =>
    play.api.mvc.Results.Unauthorized("This application required authentication")
      .withHeaders("WWW-Authenticate" -> "Basic")
  }

  override def onRouteRequest(request: RequestHeader): Option[Handler] = {

    val usernameOpt = current.configuration.getString("auth.basic.username")
    val passwordOpt = current.configuration.getString("auth.basic.password")
    if (usernameOpt.isDefined && passwordOpt.isDefined) {
      for {
        username <- usernameOpt
        password <- passwordOpt
        authstr <- request.headers.get("Authorization")
        base64 <- authstr.split(" ").drop(1).headOption
        authvals = new String(Base64.decodeBase64(base64.getBytes))
      } yield {
        authvals.split(":").toList match {
          case u :: p :: Nil if u == username && p == password => super.onRouteRequest(request)
          case _ => Some(noAuthAction)
        }
      }.getOrElse(noAuthAction)

    } else {
      super.onRouteRequest(request)
    }
  }
}