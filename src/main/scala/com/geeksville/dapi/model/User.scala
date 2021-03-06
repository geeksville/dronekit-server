package com.geeksville.dapi.model

import com.github.aselab.activerecord.Datestamps
import com.github.aselab.activerecord.annotations._
import org.squeryl.annotations.Transient
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID
import com.github.aselab.activerecord.dsl._
import grizzled.slf4j.Logging
import com.geeksville.dapi.AccessCode
import org.json4s.CustomSerializer
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s._
import com.geeksville.util.Gravatar
import java.util.Date
import scala.util.Random
import com.geeksville.akka.MockAkka
import com.geeksville.util.MD5Tools
import com.geeksville.scalatra.WebException
import java.sql.Timestamp
import com.geeksville.util.EnvelopeFactory

/**
 * Owned by a user - currently active access tokens
 *
 * @param clientId the appid for the requesting app
 *
 */
case class DBToken(@Required @Unique var accessToken: String, @Unique refreshToken: Option[String],
  clientId: String, scope: Option[String], expire: Option[Timestamp]) extends DapiRecord with Logging {
  /**
   * What vehicle made me?
   */
  lazy val user = belongsTo[User]
  val userId: Option[Long] = None

  /**
   * The user has a valid refresh token and wants to update the access token
   */
  def refreshAccessToken(): Unit = {
    accessToken = DBToken.createRandomCode()
    save
  }

  /**
   * @return a list of the scopes requested by this access token
   */
  def scopes: Seq[String] = scope.getOrElse("").split(" ").map(_.trim)

  def myUser: User = user

  def isExpired = {
    val exp = expire.getOrElse(new Timestamp(0L)).getTime

    val r = exp <= System.currentTimeMillis
    if(r)
      error(s"$this has expired!")
    r
  }
}

object DBToken extends DapiRecordCompanion[DBToken] {
  // A 1 hr lease for now...
  val leaseTime = 1000L * 60 * 60

  def create(clientId: String, scopes: Option[String]) = {
    val expire = new Timestamp(System.currentTimeMillis + leaseTime)
    val token = DBToken(DBToken.createRandomCode(), Some(DBToken.createRandomCode()), clientId, scopes, Some(expire))
    token.create
    token
  }

  def findByAccessToken(t: String) = {
    try {
      DBToken.where(_.accessToken === t).headOption
    } catch {
      case ex: IllegalArgumentException =>
        error(s"Malformed access token UUID: $t")
        None
    }
  }

  def findByRefreshToken(t: String) = {
    try {
      DBToken.where(_.refreshToken === t).headOption
    } catch {
      case ex: IllegalArgumentException =>
        error(s"Malformed refresh token UUID: $t")
        None
    }
  }

  /**
   * Generate a short alphanumeric random code
   */
  def createRandomCode() = Random.alphanumeric.take(30).mkString
}

case class User(@Required @Unique login: String,
  @Unique var email: Option[String] = None, var fullName: Option[String] = None) extends DapiRecord with Logging {
  /**
   * A user specified password
   * If null we assume invalid
   */
  @Transient
  @Length(min = 0, max = 40)
  var password: String = _

  lazy val tokens = hasMany[DBToken]

  /**
   * A hashed password or "invalid" if we want this password to never match
   */
  var hashedPassword: String = _

  /**
   * The group Ids for this user (this is a set of strings separated by comma. Can contain empty, "admin", or "preauth")
   */
  @Length(max = 40)
  var groupId: String = ""

  /// Date of last login
  var lastLoginDate: Timestamp = new Timestamp(System.currentTimeMillis)

  @Length(max = 18) /// IP address of last client login
  var lastLoginAddr: String = "unknown"

  // For vehicles and missions
  var defaultViewPrivacy: Int = AccessCode.DEFAULT_VALUE
  var defaultControlPrivacy: Int = AccessCode.DEFAULT_VALUE

  // User has confirmed their email
  var emailVerified = false

  // A sysadmin/daemon has decided this user needs a new password
  var needNewPassword = false

  var wantEmails = true

  /// If set this token can be used tempoarily by confirmPasswordReset
  var passwordResetToken: Option[Long] = None

  /// If set this was the time the password reset started (used to ignore 'too old' reset tokens)
  var passwordResetDate: Option[Timestamp] = None

  var numberOfLogins = 0

  /**
   * A URL of a small jpg for this user
   */
  def avatarImageURL = email.map(Gravatar.avatarImageUrl)

  /**
   * A URL that can be shown to the user if they want to view more details on an avatar.
   * Currently goes to gravatar.  Once we have a user profile URL in MDS we can return that instead.
   */
  def profileURL = email.map(Gravatar.profileUrl)

  /**
   * Get an ID string usable by hull.io
   *
   * For accounts that started with hull we internally prefix the ID with $h:, for other accounts we just use the whole string
  def hullId =
    if(login.startsWith(User.hullIdPrefix))
        login.substring(User.hullIdPrefix.size)
    else
        login
   */

  /**
   * All the vehicles this user owns
   */
  lazy val vehicles = hasMany[Vehicle]

  /// Is the user in the specified group name (FIXME, currently just a crude string search)
  def inGroup(groupName: String) = {
    val r = groupId.contains(groupName)
    //logger.debug(s"inGroup check for $groupName in $groupId result is $r")
    r
  }

  def isAdmin = inGroup("admin")
  def isDeveloper = inGroup("develop")
  def isResearcher = inGroup("research")
  def isPreauth = inGroup("preauth")

  /**
   * Some accounts were migrated from the old droneshare, which didn't have the concept of passwords.
   * For those accounts, if someone wants to pick the same username let them and they become the new owner of any old flights.
   */
  def isClaimable = hashedPassword == "invalid"

  def isPasswordGood(test: String) = {
    if (hashedPassword == "invalid" || hashedPassword == "invalid2") {
      logger.warn(s"Failing password test for $login, because stored password is invalid")
      false
    } else {
      // FIXME - never leave enabled in production code, because emitting psws to logs is bad juju
      // logger.warn(s"Checking password $test againsted hashed version $hashedPassword")
      BCrypt.checkpw(test.trim, hashedPassword)
    }
  }

  /**
   * Return a MD5 string which can be used to verify that the user owns this email address
   */
  def verificationCode = User.verificationFactory.encode(email.getOrElse(throw new Exception("Can't verify without email")))

  /// Check if the specified code proves the USER's email is good
  def confirmVerificationCode(code: String) {
    if (User.verificationFactory.isValid(email.getOrElse(throw new Exception("Can't verify without email")), code)) {
      emailVerified = true
      save
    } else {
      error(s"Invalid verification code $code, wanted $email")
      throw WebException(400, s"Invalid verification code")
    }
  }

  /**
   * Start a password reset session by picking a reset token and sending an email to the user
   * that contains that token (later submitted to MDS via a webform, then MDS does a post that.
   * The token will only be accepted for 48 hours
   *
   * @return token that should be included in the email to the user
   */
  def beginPasswordReset() = {
    val r = User.random.nextLong
    passwordResetToken = Some(r)
    passwordResetDate = Some(new Timestamp(System.currentTimeMillis))
    save

    r
  }

  /**
   * Update the password if the token is correct.
   * @return false for failure
   */
  def confirmPasswordReset(token: String, newPassword: String) {
    if (Some(token.toLong) != passwordResetToken || !passwordResetDate.isDefined)
      throw new Exception("Invalid password reset token")

    val timeLimit = 24 * 2 * 60 * 60 * 1000L // 48 hrs
    val expire = passwordResetDate.get.getTime + timeLimit
    if (System.currentTimeMillis > expire)
      throw new Exception("Password reset token has expired")

    password = newPassword
    passwordResetToken = None
    passwordResetDate = None
    save()
  }

  def createToken(clientId: String, scope: Option[String]) = {
    val token = DBToken.create(clientId, scope)

    token.user := this
    token.save()
    save()
    token
  }

  def getTokenByClientId(clientId: String) = {
    tokens.where(_.clientId === clientId).headOption
  }

  override def beforeSave() {

    if (password != null) {
      // A new password has been requested
      logger.warn(s"Saving $this with new password")
      hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
      password = null
    }

    if (hashedPassword == null) {
      logger.warn(s"Saving $this with invalid password")
      hashedPassword = "invalid2" // This is the new name used for guaranteed invalid passwords.  The old "invalid" string means imported from droneshare
    }

    super.beforeSave()
  }

  def getVehicle(uuid: UUID) = {
    // Vehicle.find(uuid.toString)
    debug(s"Looking for $uuid inside of $this")
    vehicles.where(_.uuid === uuid).headOption
  }

  /**
   * find a vehicle object for a specified UUID, associating it with our user if needed
   */
  def getOrCreateVehicle(uuid: UUID) = getVehicle(uuid).getOrElse {
    warn(s"Vehicle $uuid not found in $this - creating")
    val v = Vehicle(uuid).create
    vehicles << v
    v.save
    save // FIXME - do I need to explicitly save?
    v
  }

  /**
   * Return the most recent flight for this user (if known)
   */
  def newestMission: Option[Mission] = {
    implicit def dateOrdering: Ordering[Timestamp] = Ordering.fromLessThan(_ after _)

    // FIXME - this could be slow
    val missions = vehicles.flatMap(_.missions).toSeq

    val r = if (missions.isEmpty)
      None
    else
      Some(missions.minBy { m =>
        val d = m.summary.startTime.getOrElse(m.createdAt)
        //debug(s"For $m using $d")
        d
      })

    // debug(s"Returning newest mission for user: $r")
    r
  }

  override def toString() = s"User:$login(group=$groupId, email=$email, fullName=$fullName)"
}

case class UserJson(login: String,
  password: Option[String] = None, email: Option[String] = None,
  fullName: Option[String] = None, wantEmails: Option[String] = None,
  groups: Option[String] = None,

  // If a client is changing password they must also include this field (or be an admin)
  oldPassword: Option[String] = None,
  defaultViewPrivacy: Option[AccessCode.EnumVal] = None,
  defaultControlPrivacy: Option[AccessCode.EnumVal] = None)

/// We provide an initionally restricted view of users
/// If we know a viewer we will customize for them
/// @param fullVehicles if true include the full vehicle JSON (but still excluding missions)
class UserSerializer(viewer: Option[User],
                     vehicleFlavor: DeepJSON.Flavor.Type,
                     missionFlavor: DeepJSON.Flavor.Type = DeepJSON.Flavor.ShallowObj) extends CustomSerializer[User](implicit format => (
  {
    // more elegant to just make a throw away case class object and use it for the decoding
    //case JObject(JField("login", JString(s)) :: JField("fullName", JString(e)) :: Nil) =>
    case x: JValue =>
      val r = x.extract[UserJson]
      val u = User(r.login, r.email, r.fullName)
      r.password.foreach(u.password = _)
      r.groups.foreach(u.groupId = _)
      u
  },
  {
    implicit val vehicleSerializer = new VehicleSerializer(missionFlavor)

    val serializer: PartialFunction[Any, JValue] = {
      case u: User =>
        val formatWithVehicles = format + vehicleSerializer
        val vehicles = DeepJSON.asJSONArray(u.vehicles.toSeq, vehicleFlavor)(formatWithVehicles)

        var r = ("login" -> u.login) ~
          ("id" -> u.id) ~ // Frontend currently uses this for isMine() test - not sure if that is a good idea
          ("fullName" -> u.fullName) ~
          ("isAdmin" -> u.isAdmin) ~
          ("avatarImage" -> u.avatarImageURL) ~
          ("profileURL" -> u.profileURL) ~
          ("emailVerified" -> u.emailVerified) ~
          ("needNewPassword" -> u.needNewPassword) ~
          ("defaultViewPrivacy" -> AccessCode.valueOf(u.defaultViewPrivacy).toString) ~
          ("defaultControlPrivacy" -> AccessCode.valueOf(u.defaultControlPrivacy).toString) ~
          ("vehicles" -> vehicles)
          // hull support is deprecated - if we want to add it back then add back the following field so
          // the FE can use it
          // ~ ("hullId" -> Hull.generateAccessToken(u.hullId)

        val showSecrets = viewer.map { v => v.isAdmin || v.login == u.login }.getOrElse(false)
        if (showSecrets) {
          r = r ~ ("email" -> u.email) ~ ("wantEmails" -> u.wantEmails) ~ ("groups" -> u.groupId)
        }
        r
    }
    serializer
  }))

object User extends DapiRecordCompanion[User] with Logging {

  private val random = new Random(System.currentTimeMillis)

  /// We add this at the beginning of any user IDs which started with hull
  val hullProviderCode = "h"
  val auth0ProviderCode = "a"

  private def providerToPrefix(providerCode: String) = "$" + providerCode + ":"

  def findByEmail(email: String): Option[User] =
    this.where(_.email === email.toLowerCase).headOption

  /**
   * Find a user by their login name (creating the root acct if necessary)
   */
  override def find(id: String): Option[User] = {
    this.where(_.login === id.toLowerCase).headOption.orElse {
      if (id == "root") {
        debug(s"Seeding $id user")

        // If we don't find a root account - make a new one (must be a virgin/damaged DB)
        // If your run is failing on the following line, add a definition to ~/nestor.conf
        val psw = MockAkka.config.getString("dapi.defaultRootPsw")
        val u = create("root", psw, Some("kevinh@geeksville.com"), Some("Kevin Hester"), group = "admin")
        Some(u)
      } else {
        debug(s"User $id not found in DB")
        None
      }
    }
  }

  def findByLoginOrEmail(login: String) =
    find(login).orElse {
      logger.warn(s"Username $login not found, now searching for email $login")
      findByEmail(login)
    }

  /**
   * Given a validated external user id, find or create a local user record
   * @param extUserId
   * @return
   */
  def findOrCreateExternalUser(extUserId: String, provider: String, email: Option[String] = None): User = {
    val idWithPrefix = providerToPrefix(provider) + extUserId
    val r = find(idWithPrefix).getOrElse {
      val u = User(idWithPrefix)
      u.groupId = "extuser-" + provider
      u.email = email
      u.create
      u.save
      debug(s"Created new external user $u")
      u
    }
    debug(s"Using external user $r")
    r
  }

  def create(login: String, password: String = null, email: Option[String] = None, fullName: Option[String] = None, group: String = "") = {
    val u = User(login.trim.toLowerCase, email.map(_.trim.toLowerCase), fullName.map(_.trim))
    if (password != null)
      u.password = password.trim
    u.groupId = group
    u.create
    u.save
    debug(s"Created new user $u")
    u
  }

  /**
   * Return a MD5 string which can be used to verify that the user owns this email address
   */
  private val verificationFactory = new EnvelopeFactory("ab641x")
}
