package auth.handler.cache

import java.security.SecureRandom
import javax.inject.Inject

import auth.handler.AuthIdContainer
import play.api.cache.CacheApi

import scala.annotation.tailrec
import scala.concurrent.Future.{successful => immediate}
import scala.concurrent.duration._
import scala.util.Random

/**
  * Authentication id container.
  *
  * Derived in large part from play2-auth:
  *
  * https://github.com/t2v/play2-auth.git
  *
  * Modified for Play 2.5+.
  */
class CacheIdContainer @Inject()(cacheApi: CacheApi) extends AuthIdContainer {

  private val tokenSuffix = ":token"
  private val userIdSuffix = ":userId"
  private final val table = "abcdefghijklmnopqrstuvwxyz1234567890_.~*'()"
  private val random = new Random(new SecureRandom())

  override def startNewSession(userId: String, timeout: Duration) = immediate {
    removeByUserId(userId)
    val token = generate
    store(token, userId, timeout)
    token
  }

  override def remove(token: String) = immediate {
    lookup(token).foreach(unsetUserId)
    unsetToken(token)
  }

  override def get(token: String) = immediate(lookup(token))

  override def prolongTimeout(token: String, timeout: Duration) = immediate {
    lookup(token).foreach(t => store(token, t, timeout))
  }


  @tailrec
  private final def generate: String = {
    val token = Iterator.continually(random.nextInt(table.length)).map(table).take(64).mkString
    if (lookup(token).isDefined) generate else token
  }

  private def removeByUserId(userId: String) {
    cacheApi.get[String](userId + userIdSuffix).foreach(unsetToken)
    unsetUserId(userId)
  }

  private def unsetToken(token: String) = cacheApi.remove(token + tokenSuffix)

  private def unsetUserId(userId: String) = cacheApi.remove(userId + userIdSuffix)

  private def lookup(token: String) = cacheApi.get[String](token + tokenSuffix)

  private[auth] def store(token: String, userId: String, timeout: Duration) {
    cacheApi.set(token + tokenSuffix, userId, timeout)
    cacheApi.set(userId + userIdSuffix, token, timeout)
  }
}