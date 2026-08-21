package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** EXPERIMENTAL / NOT FOR PUBLIC DISTRIBUTION. This is the Codex CLI client id, not Operit's OAuth registration. */
internal object ChatGptCodexOAuthConstants {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val ISSUER_URL = "https://auth.openai.com"
    const val TOKEN_URL = "$ISSUER_URL/oauth/token"
    const val DEVICE_CODE_URL = "$ISSUER_URL/api/accounts/deviceauth/usercode"
    const val DEVICE_TOKEN_URL = "$ISSUER_URL/api/accounts/deviceauth/token"
    const val DEVICE_VERIFICATION_URL = "$ISSUER_URL/codex/device"
    const val DEVICE_REDIRECT_URI = "$ISSUER_URL/deviceauth/callback"
    const val RESPONSES_URL = "https://chatgpt.com/backend-api/wham/responses"
    const val EXPIRY_SKEW_MS = 60_000L
    const val DEVICE_LOGIN_TIMEOUT_MS = 15 * 60 * 1000L
}

data class CodexCredentials(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,
    val accountId: String?
)

data class CodexDeviceLoginChallenge(
    val verificationUrl: String,
    val userCode: String,
    internal val deviceAuthId: String,
    internal val pollIntervalSeconds: Long
)

/** OAuth credential owner for the experimental ChatGPT Codex provider. Never log token values. */
class ChatGptCodexAuth private constructor(context: Context) {
    private val refreshMutex = Mutex()
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        "chatgpt_codex_oauth",
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val credentialsState = MutableStateFlow(loadCredentials())

    val credentials: StateFlow<CodexCredentials?> = credentialsState

    fun getCredentials(): CodexCredentials? = credentialsState.value

    fun isLoggedIn(): Boolean = getCredentials() != null

    fun logout() {
        preferences.edit().clear().apply()
        credentialsState.value = null
    }

    suspend fun beginLogin(): CodexDeviceLoginChallenge {
        val response = postJson(
            ChatGptCodexOAuthConstants.DEVICE_CODE_URL,
            JSONObject().put("client_id", ChatGptCodexOAuthConstants.CLIENT_ID)
        )
        response.use {
            if (!it.isSuccessful) {
                throw IllegalStateException("ChatGPT device authorization could not be started (${it.code})")
            }
            val body = JSONObject(it.body?.string().orEmpty())
            val deviceAuthId = body.requiredString("device_auth_id")
            val userCode = body.requiredString("user_code")
            val interval = body.optString("interval").toLongOrNull()
                ?: throw IllegalStateException("ChatGPT device authorization returned an invalid poll interval")
            return CodexDeviceLoginChallenge(
                verificationUrl = ChatGptCodexOAuthConstants.DEVICE_VERIFICATION_URL,
                userCode = userCode,
                deviceAuthId = deviceAuthId,
                pollIntervalSeconds = interval.coerceAtLeast(1L)
            )
        }
    }

    suspend fun completeLogin(challenge: CodexDeviceLoginChallenge): CodexCredentials {
        val deadline = System.currentTimeMillis() + ChatGptCodexOAuthConstants.DEVICE_LOGIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val response = postJson(
                ChatGptCodexOAuthConstants.DEVICE_TOKEN_URL,
                JSONObject()
                    .put("device_auth_id", challenge.deviceAuthId)
                    .put("user_code", challenge.userCode)
            )
            response.use {
                if (it.isSuccessful) {
                    val body = JSONObject(it.body?.string().orEmpty())
                    return exchangeAuthorizationCode(
                        code = body.requiredString("authorization_code"),
                        codeVerifier = body.requiredString("code_verifier")
                    )
                }
                if (it.code != 403 && it.code != 404) {
                    throw IllegalStateException("ChatGPT device authorization failed (${it.code})")
                }
            }
            delay(challenge.pollIntervalSeconds * 1000L)
        }
        throw IllegalStateException("ChatGPT authorization timed out")
    }

    suspend fun getValidAccessToken(): String {
        val credentials = getCredentials() ?: throw IllegalStateException("ChatGPT Codex login is required")
        if (credentials.expiresAt > System.currentTimeMillis() + ChatGptCodexOAuthConstants.EXPIRY_SKEW_MS) {
            return credentials.accessToken
        }
        return refreshAccessToken().accessToken
    }

    suspend fun refreshAccessToken(force: Boolean = false): CodexCredentials = refreshMutex.withLock {
        val current = getCredentials() ?: throw IllegalStateException("ChatGPT Codex login is required")
        if (!force && current.expiresAt > System.currentTimeMillis() + ChatGptCodexOAuthConstants.EXPIRY_SKEW_MS) {
            return current
        }
        val refreshToken = current.refreshToken ?: run {
            logout()
            throw IllegalStateException("ChatGPT login expired")
        }
        try {
            val response = postJson(
                ChatGptCodexOAuthConstants.TOKEN_URL,
                JSONObject()
                    .put("client_id", ChatGptCodexOAuthConstants.CLIENT_ID)
                    .put("grant_type", "refresh_token")
                    .put("refresh_token", refreshToken)
            )
            response.use {
                if (!it.isSuccessful) {
                    val errorBody = it.body?.string().orEmpty()
                    val errorCode = runCatching { JSONObject(errorBody).optString("error") }.getOrDefault("")
                    if (errorCode == "invalid_grant") {
                        logout()
                        throw IllegalStateException("ChatGPT login expired")
                    }
                    throw IllegalStateException("ChatGPT token refresh failed (${it.code})")
                }
                save(parseCredentials(JSONObject(it.body?.string().orEmpty())))
            }
        } catch (error: Exception) {
            AppLogger.w("ChatGptCodexAuth", "ChatGPT Codex token refresh failed", error)
            throw error
        }
    }

    private suspend fun exchangeAuthorizationCode(code: String, codeVerifier: String): CodexCredentials {
        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url(ChatGptCodexOAuthConstants.TOKEN_URL)
                    .post(
                        FormBody.Builder()
                            .add("grant_type", "authorization_code")
                            .add("code", code)
                            .add("redirect_uri", ChatGptCodexOAuthConstants.DEVICE_REDIRECT_URI)
                            .add("client_id", ChatGptCodexOAuthConstants.CLIENT_ID)
                            .add("code_verifier", codeVerifier)
                            .build()
                    )
                    .build()
            ).execute()
        }
        response.use {
            if (!it.isSuccessful) {
                throw IllegalStateException("ChatGPT authorization code exchange failed (${it.code})")
            }
            return save(parseCredentials(JSONObject(it.body?.string().orEmpty())))
        }
    }

    private suspend fun postJson(url: String, body: JSONObject) = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
    }

    private fun parseCredentials(body: JSONObject): CodexCredentials {
        val idToken = body.requiredString("id_token")
        val expiresInSeconds = body.optLong("expires_in", 0)
        if (expiresInSeconds <= 0) {
            throw IllegalStateException("ChatGPT token response did not include a valid expiry")
        }
        return CodexCredentials(
            accessToken = body.requiredString("access_token"),
            refreshToken = body.requiredString("refresh_token"),
            expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000L,
            accountId = extractAccountId(idToken)
        )
    }

    private fun save(credentials: CodexCredentials): CodexCredentials {
        preferences.edit()
            .putString("access_token", credentials.accessToken)
            .putString("refresh_token", credentials.refreshToken)
            .putLong("expires_at", credentials.expiresAt)
            .putString("account_id", credentials.accountId)
            .apply()
        credentialsState.value = credentials
        return credentials
    }

    private fun loadCredentials(): CodexCredentials? {
        val accessToken = preferences.getString("access_token", null) ?: return null
        return CodexCredentials(
            accessToken = accessToken,
            refreshToken = preferences.getString("refresh_token", null),
            expiresAt = preferences.getLong("expires_at", 0),
            accountId = preferences.getString("account_id", null)
        )
    }

    private fun extractAccountId(idToken: String): String? = try {
        val payload = idToken.split('.').getOrNull(1) ?: return null
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        JSONObject(decoded.toString(Charsets.UTF_8)).optString("chatgpt_account_id").ifBlank { null }
    } catch (_: Exception) {
        null
    }

    private fun JSONObject.requiredString(name: String): String = optString(name).trim().ifBlank {
        throw IllegalStateException("ChatGPT OAuth response did not include $name")
    }

    companion object {
        @Volatile private var instance: ChatGptCodexAuth? = null

        fun getInstance(context: Context): ChatGptCodexAuth = instance ?: synchronized(this) {
            instance ?: ChatGptCodexAuth(context.applicationContext).also { instance = it }
        }
    }
}
