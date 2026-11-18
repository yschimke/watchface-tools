package ee.schimke.watchface.push.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What the watch keeps after linking: the device token, a display name for the account, and the
 * catalog [server] that issued the token. A token is only ever sent to its own server.
 */
data class LinkedAccount(val deviceToken: String, val accountName: String?, val server: String)

interface TokenStore {
  val account: Flow<LinkedAccount?>

  suspend fun save(account: LinkedAccount)

  suspend fun clear()
}

class InMemoryTokenStore(initial: LinkedAccount? = null) : TokenStore {
  private val state = MutableStateFlow(initial)
  override val account: Flow<LinkedAccount?> = state

  override suspend fun save(account: LinkedAccount) {
    state.value = account
  }

  override suspend fun clear() {
    state.value = null
  }
}

private val Context.linkStore: DataStore<Preferences> by preferencesDataStore("link")

/**
 * Device token in Preferences DataStore, encrypted with an AES-GCM key that lives in the Android
 * Keystore (never leaves secure hardware where available). Undecryptable data (e.g. after a restore
 * onto another device) reads as "not linked".
 */
class DataStoreTokenStore(context: Context) : TokenStore {
  private val store = context.applicationContext.linkStore
  private val tokenKey = stringPreferencesKey("token")
  private val nameKey = stringPreferencesKey("name")
  private val serverKey = stringPreferencesKey("server")

  override val account: Flow<LinkedAccount?> =
    store.data.map { prefs ->
      val token = prefs[tokenKey]?.let { runCatching { decrypt(it) }.getOrNull() }
      // A token saved before tokens recorded their server has no known owner: read as unlinked.
      val server = prefs[serverKey]
      if (token != null && server != null) LinkedAccount(token, prefs[nameKey], server) else null
    }

  override suspend fun save(account: LinkedAccount) {
    val encrypted = encrypt(account.deviceToken)
    store.edit {
      it[tokenKey] = encrypted
      it[serverKey] = account.server
      account.accountName?.let { name -> it[nameKey] = name } ?: it.remove(nameKey)
    }
  }

  override suspend fun clear() {
    store.edit { it.clear() }
  }

  suspend fun current(): LinkedAccount? = account.first()

  private fun key(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
      return it
    }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
      .apply {
        init(
          KeyGenParameterSpec.Builder(
              KEY_ALIAS,
              KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build(),
        )
      }
      .generateKey()
  }

  private fun encrypt(plain: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
    val out = cipher.iv + cipher.doFinal(plain.toByteArray())
    return Base64.encodeToString(out, Base64.NO_WRAP)
  }

  private fun decrypt(encoded: String): String {
    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
    val cipher =
      Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
      }
    return String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE))
  }

  private companion object {
    const val KEY_ALIAS = "watchface_push_device_token"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_SIZE = 12
  }
}
