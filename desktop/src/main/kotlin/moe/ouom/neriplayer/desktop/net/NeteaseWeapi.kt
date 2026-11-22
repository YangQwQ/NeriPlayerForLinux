package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 weapi 参数加密（web 端 `/weapi/...` 接口使用）。
 *
 * 与 Android `NeteaseCrypto.weApiEncrypt` 对齐：随机 16 位 key → 双层 AES-CBC →
 * 反转 key 后 RSA 无填充加密得到 encSecKey。桌面端写歌单接口必须走这一层，
 * 明文 `/api/...` 不支持写操作。
 */
object NeteaseWeapi {

    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val PUBLIC_KEY_BASE64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFb" +
            "t7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZ" +
            "MldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"

    private val secureRandom = SecureRandom()
    private val publicKey by lazy {
        val spec = java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_BASE64))
        java.security.KeyFactory.getInstance("RSA").generatePublic(spec) as java.security.interfaces.RSAPublicKey
    }

    /** 按 Map 插入顺序序列化成紧凑 JSON；明文参数顺序会影响服务端校验。 */
    fun encrypt(params: Map<String, Any>): Map<String, String> {
        val json = buildJsonObject {
            params.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Number -> put(key, value)
                    is Boolean -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }.toString()
        val secret = randomKey()
        val first = aesCbcBase64(json, PRESET_KEY)
        val second = aesCbcBase64(first, secret)
        return mapOf(
            "params" to second,
            "encSecKey" to rsaNoPadding(secret.reversed()),
        )
    }

    private fun randomKey(): String = buildString(16) {
        repeat(16) { append(BASE62[secureRandom.nextInt(BASE62.length)]) }
    }

    private fun aesCbcBase64(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8)),
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /** 官方 weapi 用 RSA/ECB/NOPADDING， JDK 无该变换，直接做模幂。 */
    private fun rsaNoPadding(text: String): String {
        val value = BigInteger(1, text.toByteArray(Charsets.UTF_8))
        val encrypted = value.modPow(publicKey.publicExponent, publicKey.modulus)
        val size = (publicKey.modulus.bitLength() + 7) / 8
        val bytes = encrypted.toByteArray().let { raw ->
            when {
                raw.size > size -> raw.copyOfRange(raw.size - size, raw.size)
                raw.size < size -> ByteArray(size).also { System.arraycopy(raw, 0, it, size - raw.size, raw.size) }
                else -> raw
            }
        }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
