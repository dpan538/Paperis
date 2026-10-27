# Paperis feedback relay

The Android feedback editor keeps a local draft and sends only the entered text, app version and stable retry ID. This service creates a public Issue in `dpan538/Paperis`; both the relay and Android client verify the returned Issue before reporting success. Failed or ambiguous requests retain the draft and retry identity.

## Configure Production

1. [Create a fine-grained GitHub token](https://github.com/settings/personal-access-tokens/new?name=Paperis%20Feedback&description=Paperis%20feedback%20issue%20relay&target_name=dpan538&expires_in=90&issues=write). Select only the Paperis repository and grant Issues read/write. No source-code write permission is needed.
2. Add `PAPERIS_GITHUB_TOKEN` as a sensitive **Production** environment variable in the [Paperis feedback project](https://vercel.com/dpan538s-projects/paperis-feedback/settings/environment-variables).
3. Deploy this directory as the Vercel project root, or redeploy after changing the environment variable. The Python function needs no database. Keep preview deployments protected and the production API reachable by the app.

Never put the token in Android resources, source, documentation or a client-side environment variable. `.env.example` contains the variable name only. Token expiry or revocation causes submissions to fail while drafts remain on the device.

## Endpoints

- `GET /healthz`: service identity and a boolean indicating whether the server variable exists. This does not verify GitHub permission and never returns its value.
- `POST /v1/feedback`: JSON containing exactly `body`, `version`, `requestId`. At most 1000 Unicode code points and an 8 KiB request. The response reports acceptance only after an independently fetched GitHub Issue contains the matching request/content marker.

Retries reconcile against existing Issues. The service applies a repository-wide 20-feedback/hour guard; it is a small public feedback relay, not an authenticated support system. Do not send book contents or personal information. The recent-Issue reconciliation scan is bounded and fails closed when its window is exhausted.

## Test locally

```sh
python3 -m unittest discover -s feedback-service -p 'test_*.py'
```

Tests use a fake GitHub transport and a local HTTP server. They cover malformed input, size limits, exact Unicode content, fixed repository scope, missing credentials, duplicate retries, ambiguous writes, verification failure and rate limiting. They never create public Issues. Run the Android `experience` suite for draft preservation and receipt handling in the native editor.
