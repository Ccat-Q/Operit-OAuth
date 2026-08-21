package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** EXPERIMENTAL / NOT FOR PUBLIC DISTRIBUTION. This is the Codex CLI client id, not Operit's OAuth registration. */
internal object ChatGptCodexOAuthConstants {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    const val RESPONSES_URL = "https://chatgpt.com/backend-api/wham/responses"
    const val EXPIRY_SKEW_MS = 60_000L
}

data class CodexCredentials(val accessToken: String, val refreshToken: String?, val expiresAt: Long, val accountId: String?)

/** OAuth credential owner for the experimental ChatGPT Codex provider. Never log token values. */
class ChatGptCodexAuth private constructor(context: Context) {
    private val refreshMutex = Mutex()
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val preferences = EncryptedSharedPreferences.create(context.applicationContext, "chatgpt_codex_oauth", MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(), EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun getCredentials(): CodexCredentials? { val accessToken = preferences.getString("access_token", null) ?: return null; return CodexCredentials(accessToken, preferences.getString("refresh_token", null), preferences.getLong("expires_at", 0), preferences.getString("account_id", null)) }
    fun isLoggedIn(): Boolean = getCredentials() != null
    fun logout() { preferences.edit().clear().apply() }
    suspend fun getValidAccessToken(): String { val credentials = getCredentials() ?: throw IllegalStateException("ChatGPT Codex login is required"); if (credentials.expiresAt > System.currentTimeMillis() + ChatGptCodexOAuthConstants.EXPIRY_SKEW_MS) return credentials.accessToken; return refreshAccessToken().accessToken }
    suspend fun refreshAccessToken(): CodexCredentials = refreshMutex.withLock {
        val current = getCredentials() ?: throw IllegalStateException("ChatGPT Codex login is required")
        if (current.expiresAt > System.currentTimeMillis() + ChatGptCodexOAuthConstants.EXPIRY_SKEW_MS) return current
        val refreshToken = current.refreshToken ?: run { logout(); throw IllegalStateException("ChatGPT Codex login expired") }
        try {
            val response = withContext(Dispatchers.IO) { client.newCall(Request.Builder().url(ChatGptCodexOAuthConstants.TOKEN_URL).post(FormBody.Builder().add("client_id", ChatGptCodexOAuthConstants.CLIENT_ID).add("grant_type", "refresh_token").add("refresh_token", refreshToken).build()).build()).execute() }
            response.use { if (!it.isSuccessful) throw IllegalStateException("ChatGPT token refresh failed (${it.code})"); val json = JSONObject(it.body?.string().orEmpty()); val updated = CodexCredentials(json.getString("access_token"), json.optString("refresh_token").ifBlank { refreshToken }, System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000, current.accountId); save(updated); updated }
        } catch (error: Exception) { AppLogger.w("ChatGptCodexAuth", "ChatGPT Codex token refresh failed", error); logout(); throw error }
    }
    fun save(credentials: CodexCredentials) { preferences.edit().putString("access_token", credentials.accessToken).putString("refresh_token", credentials.refreshToken).putLong("expires_at", credentials.expiresAt).putString("account_id", credentials.accountId).apply() }
    companion object { fun getInstance(context: Context) = ChatGptCodexAuth(context) }
}
