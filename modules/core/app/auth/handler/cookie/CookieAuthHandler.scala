package auth.handler.cookie

import javax.inject.Inject

import auth.AccountManager
import auth.handler.{AuthHandler, AuthIdContainer, TokenAccessor}
import models.Account
import play.api.mvc.RequestHeader
import play.api.{Application, Configuration, Mode}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Authentication handler.
  *
  * Derived in large part from play2-auth:
  *
  * https://github.com/t2v/play2-auth.git
  *
  * Modified for Play 2.5+.
  */
case class CookieAuthHandler @Inject()(
  app: Application,
  idContainer: AuthIdContainer,
  tokenAccessor: TokenAccessor,
  accountManager: AccountManager,
  config: Configuration,
  executionContext: ExecutionContext
) extends AuthHandler {

  implicit val ec: ExecutionContext = executionContext

  import scala.concurrent.duration._

  // Default - one day...
  override def sessionTimeout: FiniteDuration =
    config.getInt("auth.session.timeout").getOrElse(60 * 60 * 24).seconds

  override def restoreAccount(implicit request: RequestHeader): Future[(Option[Account], ResultUpdater)] = {
    (for {
      token <- extractToken(request)
    } yield for {
      Some(userId) <- idContainer.get(token)
      userOpt <- accountManager.findById(userId)
      _ <- idContainer.prolongTimeout(token, sessionTimeout)
    } yield userOpt -> tokenAccessor.put(token) _
      ) getOrElse {
      Future.successful(Option.empty -> identity)
    }
  }

  private[auth] def extractToken(request: RequestHeader): Option[String] = {
    if (app.mode == Mode.Test) {
      request.headers.get("PLAY2_AUTH_TEST_TOKEN") orElse tokenAccessor.extract(request)
    } else {
      tokenAccessor.extract(request)
    }
  }
}