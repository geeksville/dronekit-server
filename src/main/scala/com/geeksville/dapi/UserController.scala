package com.geeksville.dapi

import org.scalatra._
import org.scalatra.swagger.SwaggerSupport
import org.json4s.{ DefaultFormats, Formats }
import org.scalatra.json._
import org.scalatra.swagger.Swagger
import com.geeksville.dapi.model._
import org.json4s.JsonAST.JObject
import org.json4s._
import com.geeksville.json.GeeksvilleFormats
import java.util.UUID
import com.geeksville.json.EnumSerializer

class UserController(implicit swagger: Swagger) extends ActiveRecordController[User, UserJson]("user", swagger, User) {
  override val blacklist = Set("hashedPassword", "password", "groupId")

  override protected def requireWriteAccess(o: User) = {
    requireBeOwnerOrAdmin(o.id)
    super.requireWriteAccess(o)
  }

  override protected def toJSON(o: Any): JValue = {
    Extraction.decompose(o)(DefaultFormats ++ GeeksvilleFormats + EnumSerializer.create(AccessCode) +
      new UserSerializer(Option(user), DeepJSON.Flavor.Deep))
  }

  /// Subclasses can provide suitable behavior if they want to allow PUTs to /:id to result in updating objects.
  /// Implementations should return the updated object
  override protected def updateObject(o: User, payload: JObject) = {
    val r = payload.extract[UserJson]

    // Handle password updates
    r.password.foreach { newPsw =>
      val oldPwGood = r.oldPassword.map(o.isPasswordGood).getOrElse(user.isAdmin)
      if (!oldPwGood)
        haltUnauthorized("Invalid oldPassword")

      o.password = newPsw
      o.save
    }

    // If the logged in user is an admin let them change groups
    r.groups.foreach { newGroup =>
      if (!user.isAdmin)
        haltUnauthorized("Invalid access")

      o.groupId = newGroup
      o.save
    }

    r.defaultViewPrivacy.foreach { o.defaultViewPrivacy = _ }

    o
  }
}

