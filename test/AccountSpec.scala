package test

import play.api.db.DB
import anorm._
import anorm.SqlParser._
import helpers.WithFixures
import models.{Account, AccountDAO}
import models.sql.{OAuth2Association, OpenIDAssociation}
import play.api.test.PlaySpecification


/**
 * Spec for testing individual data access components work as expected.
 */
class AccountSpec extends PlaySpecification {

  "account db" should {
    "load fixtures with the right number of accounts" in new WithFixures {
      DB.withConnection { implicit connection =>
        SQL("select count(*) from users").as(scalar[Long].single) must equalTo(3L)
      }
    }

    "find accounts by id and email" in new WithFixures {
      DB.withConnection { implicit connection =>
        val userDAO: AccountDAO = play.api.Play.current.plugin(classOf[AccountDAO]).get
        userDAO.findByProfileId(mocks.privilegedUser.id) must beSome
        userDAO.findByEmail(mocks.privilegedUser.email) must beSome
      }
    }

    "allow setting user's passwords" in new WithFixures {
      DB.withConnection { implicit connection =>
        val userDAO: AccountDAO = play.api.Play.current.plugin(classOf[AccountDAO]).get
        val userOpt: Option[Account] = userDAO.findByEmail(mocks.privilegedUser.email)
        userOpt must beSome.which { user =>
          val hashedPw = Account.hashPassword("foobar")
          user.setPassword(hashedPw)
          SQL(
            """select count(users.id) from users, user_auth
             where users.id = user_auth.id
             and user_auth.data = {pw}
            """).on('pw -> hashedPw.toString)
            .as(scalar[Long].single) must equalTo(1L)
        }
      }
    }
  }

  "openid assoc" should {
    "find accounts by openid_url and allow adding another" in new WithFixures {
      val assoc = OpenIDAssociation.findByUrl(mocks.privilegedUser.id + "-openid-test-url")
      assoc must beSome.which { ass =>
        ass.user must beSome.which { u =>
          u.email must beEqualTo(mocks.privilegedUser.email)
        }
      }

      val user = assoc.get.user.get
      OpenIDAssociation.addAssociation(user, "another-test-url")
      val assoc2 = OpenIDAssociation.findByUrl("another-test-url")
      assoc2 must beSome.which { ass =>
        ass.user must beSome.which { u =>
          u must equalTo(user)
        }
      }
    }
  }

  "oauth2 assoc" should {
    "find accounts by oauth2 provider info and allow adding another" in new WithFixures {
      val assoc = OAuth2Association.findByProviderInfo("1234", "google")
      assoc must beSome.which { ass =>
        ass.user must beSome.which { u =>
          u.email must beEqualTo(mocks.privilegedUser.email)
        }
      }

      val user = assoc.get.user.get
      OAuth2Association.addAssociation(user, "4321", "facebook")
      val assoc2 = OAuth2Association.findByProviderInfo("4321", "facebook")
      assoc2 must beSome.which { ass =>
        ass.user must beSome.which { u =>
          u must equalTo(user)
        }
      }
    }
  }
}
