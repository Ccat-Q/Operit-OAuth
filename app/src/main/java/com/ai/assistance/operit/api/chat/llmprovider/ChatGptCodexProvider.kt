package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request

/** Experimental provider for the non-public ChatGPT Codex Responses backend. */
class ChatGptCodexProvider(
    private val context: Context,
    modelName: String,
    client: OkHttpClient,
    supportsVision: Boolean,
    supportsAudio: Boolean,
    supportsVideo: Boolean,
    enableToolCall: Boolean
) : OpenAIResponsesProvider(
    responsesApiEndpoint = ChatGptCodexOAuthConstants.RESPONSES_URL,
    apiKeyProvider = SingleApiKeyProvider(""),
    modelName = modelName,
    client = client,
    responsesProviderType = com.ai.assistance.operit.data.model.ApiProviderType.CHATGPT_CODEX,
    supportsVision = supportsVision,
    supportsAudio = supportsAudio,
    supportsVideo = supportsVideo,
    enableToolCall = enableToolCall
) {
    override fun applyAuthenticationHeaders(builder: Request.Builder, currentApiKey: String) {
        val auth = ChatGptCodexAuth.getInstance(context)
        val accessToken = runBlocking { auth.getValidAccessToken() }
        builder.header("Authorization", "Bearer $accessToken")
        auth.getCredentials()?.accountId?.takeIf(String::isNotBlank)?.let { builder.header("ChatGPT-Account-ID", it) }
    }

    override suspend fun refreshAuthenticationAfterUnauthorized(): Boolean = try {
        ChatGptCodexAuth.getInstance(context).refreshAccessToken(force = true)
        true
    } catch (_: Exception) {
        false
    }
}
