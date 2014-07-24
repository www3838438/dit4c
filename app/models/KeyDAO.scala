package models

import com.google.inject.Inject
import scala.concurrent.ExecutionContext
import providers.db.CouchDB
import scala.concurrent.Future
import play.api.libs.ws.WS
import play.api.libs.json._
import play.api.mvc.Results.EmptyContent
import scala.util.Try
import java.security.interfaces.RSAPrivateKey
import java.util.Date
import java.util.TimeZone
import play.api.libs.ws.WSRequestHolder
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import java.security.interfaces.RSAPublicKey
import java.security.KeyPairGenerator
import com.nimbusds.jose.JWSAlgorithm

class KeyDAO @Inject() (protected val db: CouchDB.Database)
  (implicit protected val ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._
  import play.api.Play.current

  /**
   * @param namespace   Arbitrary string to include in key IDs
   * @param keyLength   Length of key to generate (default: 4096)
   * @return Future key object
   */
  def create(namespace: String, keyLength: Int = 4096): Future[Key] = {
    for {
      id <- db.newID
      keyPair <- createNewKeyPair(keyLength)
      createdAt = DateTime.now(DateTimeZone.UTC)
      key = KeyImpl(id, None, namespace, createdAt, false, keyPair)
      response <- WS.url(s"${db.baseURL}/$id").put(Json.toJson(key))
    } yield {
      response.status match {
        case 201 =>
          // Update with revision
          val rev = (response.json \ "rev").as[Option[String]]
          key.copy(_rev = rev)
      }
    }
  }

  def list: Future[Seq[Key]] = {
    val tempView = TemporaryView(views.js.models.Key_list_map())
    WS.url(s"${db.baseURL}/_temp_view")
      .post(Json.toJson(tempView))
      .map { response =>
        (response.json \ "rows" \\ "value").flatMap(fromJson[KeyImpl])
      }
  }

  private def createNewKeyPair(keyLength: Int): Future[RSAKey] = Future {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(keyLength)
    val kp = generator.generateKeyPair
    val pub = kp.getPublic.asInstanceOf[RSAPublicKey]
    val priv = kp.getPrivate.asInstanceOf[RSAPrivateKey]
    new RSAKey(pub, priv, null, null, null, null, null, null, null)
  }

  implicit val dateTimeFormat: Format[DateTime] = new Format[DateTime]() {
    def reads(json: JsValue): JsResult[DateTime] =
      try {
        JsSuccess(DateTime.parse(json.as[String]))
      } catch {
        case _: IllegalArgumentException => JsError()
      }

    def writes(o: DateTime): JsValue =
      JsString(o.toString)
  }

  implicit val rsaKeyFormat: Format[RSAKey] = new Format[RSAKey]() {
    def reads(json: JsValue): JsResult[RSAKey] =
      JsSuccess(JWK.parse(Json.stringify(json)).asInstanceOf[RSAKey])

    def writes(o: RSAKey): JsValue = Json.parse(o.toJSONString)
  }

  implicit val keyFormat: Format[KeyImpl] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "namespace").format[String] and
    (__ \ "createdAt").format[DateTime] and
    (__ \ "retired").format[Boolean] and
    (__ \ "keyPair").format[RSAKey]
  )(KeyImpl.apply _, unlift(KeyImpl.unapply))
    .withTypeAttribute("Key")

  case class KeyImpl(
      id: String,
      _rev: Option[String],
      namespace: String,
      createdAt: DateTime,
      retired: Boolean,
      keyPair: RSAKey)(implicit ec: ExecutionContext) extends Key {
    import play.api.libs.functional.syntax._
    import play.api.Play.current

    override def publicId: String = s"$namespace $createdAt [$id]"

    override def retire: Future[Key] =
      for {
        id <- db.newID
        key = this.copy(retired = true)
        response <- WS.url(s"${db.baseURL}/$id").put(Json.toJson(key))
      } yield {
        response.status match {
          case 201 =>
            // Update with revision
            val rev = (response.json \ "rev").as[Option[String]]
            key.copy(_rev = rev)
        }
      }

    override def delete: Future[Unit] = utils.delete(id, _rev.get)

    override def toJWK = new RSAKey(
      keyPair.toRSAPublicKey,
      keyPair.toRSAPrivateKey,
      keyPair.getKeyUse,
      keyPair.getKeyOperations,
      keyPair.getAlgorithm,
      publicId,
      null, null, null)

  }

}

trait Key {

  def id: String
  def _rev: Option[String]
  def namespace: String
  def createdAt: DateTime
  def retired: Boolean

  def publicId: String
  def toJWK: RSAKey

  def retire: Future[Key]
  def delete: Future[Unit]

}

