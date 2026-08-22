---
fork: Operit-OAuth
status: in-progress
---

# ChatGPT Codex OAuth experimental provider

The repository currently supports OpenAI Responses requests with API-key authentication, but has no isolated ChatGPT Codex OAuth provider.

This change will add an experimental, separately selectable provider. It will keep credentials outside `ModelConfigData`, reuse the existing Responses, streaming, reasoning, and structured-tool pipeline, and add OAuth login, encrypted credential persistence, refresh coordination, and provider-specific UI.

## Scope

1. Map the existing provider, endpoint, configuration, and UI integration points.
2. Verify the current Codex CLI OAuth flow and backend request contract from OpenAI's source.
3. Add the provider type, defaults, factory routing, endpoint routing, and settings UI.
4. Add secure OAuth credentials, the Codex Device Flow authorization-code exchange, refresh coordination, and one 401 refresh retry.
5. Reuse the Responses provider for normal, streaming, reasoning, and tool-call requests; add only Codex-specific request and header differences.
6. Compile and run focused tests/checks requested for this task.
7. Fetch the authenticated Codex model catalog and keep native Markdown stream parsing off the UI thread.

## Completion criteria

- The new provider does not use or persist a standard OpenAI API key.
- OAuth secrets are encrypted at rest and never logged.
- Existing providers remain unchanged in their public configuration behavior.
- Documentation records the experimental and non-public-interface risk.

## Implementation notes

- The implementation follows the current Codex Device Flow: request a user code, open `https://auth.openai.com/codex/device`, poll for the short-lived authorization code, then exchange it with the server-provided PKCE verifier.
- `access_token` and `refresh_token` are stored only in `EncryptedSharedPreferences` backed by Android Keystore. The `id_token` is used only to extract `chatgpt_account_id` and is not persisted. A refresh only clears the saved login state when OAuth explicitly reports `invalid_grant`; transient refresh failures leave the encrypted credentials intact.
- The Codex model catalog is fetched from the authenticated `https://chatgpt.com/backend-api/codex/models?client_version=99.99.99` route and only exposes models the service marks as visible and API-supported. The same non-public Codex backend is used for Responses; this provider is experimental and must not be presented as the public OpenAI API.
- Native Markdown session creation and incremental parsing run on `Dispatchers.Default`; the Compose main thread only consumes parsed stream groups. This prevents a native parser wait from blocking input dispatch and causing an ANR.
- Codex Responses requests explicitly set `store: false`, as required by the backend. This setting is isolated to the experimental provider and does not change standard OpenAI Responses requests.
- Codex also requires `stream: true` for background Responses such as title generation and connection tests. `OpenAIProvider.resolveStreamMode` keeps the request flag and response parser aligned only for this provider.

## Manual validation

1. Build and install the `app` debug variant, then add or edit a model configuration and select **ChatGPT Codex**.
2. Leave the API-key field empty, select an available Codex model name, and choose **使用 ChatGPT 登录**.
3. Confirm that the browser opens `auth.openai.com/codex/device`; complete sign-in with the intended ChatGPT account and enter the one-time code shown in Operit.
4. Return to Operit and confirm that the status changes to **已登录** without displaying any token value.
5. Send a streaming request, then a request that invokes an Operit tool. Verify that text deltas, reasoning, the function call, function output, and the final answer are delivered through the existing Responses pipeline.
6. Wait until the access token is within the refresh window or revoke it in a test account, then send a request. Confirm that Operit refreshes once; an invalid refresh token must clear the login status and require a new sign-in.
7. Treat 403 as an entitlement/model-permission error, 429 as quota/rate limiting, and 404 as model/backend availability. Do not attempt to bypass any of these server-side decisions.
