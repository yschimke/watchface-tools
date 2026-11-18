package ee.schimke.watchface.push.link

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The catalog server the watch talks to. Emits [default] until one is chosen. */
interface ServerStore {
  val server: Flow<String>

  suspend fun select(server: String)
}

class InMemoryServerStore(initial: String) : ServerStore {
  private val state = MutableStateFlow(initial)
  override val server: Flow<String> = state

  override suspend fun select(server: String) {
    state.value = server
  }
}

private val Context.serverStore: DataStore<Preferences> by preferencesDataStore("server")

/** The chosen server in Preferences DataStore; not a secret, so stored in the clear. */
class DataStoreServerStore(context: Context, private val default: String) : ServerStore {
  private val store = context.applicationContext.serverStore
  private val key = stringPreferencesKey("url")

  override val server: Flow<String> = store.data.map { it[key] ?: default }

  override suspend fun select(server: String) {
    store.edit { if (server == default) it.remove(key) else it[key] = server }
  }
}
