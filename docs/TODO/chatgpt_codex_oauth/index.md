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
4. Add secure OAuth credentials, PKCE browser authorization, token exchange/refresh, and one 401 refresh retry.
5. Reuse the Responses provider for normal, streaming, reasoning, and tool-call requests; add only Codex-specific request and header differences.
6. Compile and run focused tests/checks requested for this task.

## Completion criteria

- The new provider does not use or persist a standard OpenAI API key.
- OAuth secrets are encrypted at rest and never logged.
- Existing providers remain unchanged in their public configuration behavior.
- Documentation records the experimental and non-public-interface risk.
