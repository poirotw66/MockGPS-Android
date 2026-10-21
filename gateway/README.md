# BloomWalk GPS gateway

Cloudflare Worker that fronts Geoapify search/routing and Google Play purchase verification for BloomWalk GPS. This package is designed for **Workers Free** and SQLite Durable Objects. It does not enable paid Cloudflare plans, auto top-up, or a public Nominatim/FOSSGIS fallback.

## Status

- Production feature flags default to **false**: `searchEnabled`, `routingEnabled`, `purchasesEnabled`, `adsEnabled`.
- Local development uses provider **stubs** (`GEO_PROVIDER_MODE=stub`, `PLAY_API_MODE=stub`).
- Missing Geoapify keys do **not** invent successful geocoding. Live mode without a key returns `PROVIDER_UNAVAILABLE`.
- **B01 has no provider hard cap.** Routing dispatch is allowed only for the owner-accepted 25-credit ledger reservation (`OWNER_ACCEPTED_SOFT_QUOTA_ROUTE_25`) or a test-only accounting policy. Any other route bound stays `Unsupported`. `ROUTING_ENABLED` stays false until a live deploy. See [../docs/GEOAPIFY_TERMS_COST_EVIDENCE.md](../docs/GEOAPIFY_TERMS_COST_EVIDENCE.md).

## Endpoints

| Method | Path                   | Notes                                                              |
| ------ | ---------------------- | ------------------------------------------------------------------ |
| `GET`  | `/v1/config`           | Public capabilities. Short cache, 5 minutes.                       |
| `POST` | `/v1/search`           | Body only. Never put the query in the gateway URL.                 |
| `POST` | `/v1/route`            | Body only. Refused when the routing credit bound is `Unsupported`. |
| `POST` | `/v1/purchases/verify` | Independent limiter. Does not consume geo credits.                 |

Required geo/purchase headers: `X-Install-ID`, `X-Client-Version`, `X-Request-ID` (echoed).

## Local stub

The default Geoapify stub returns the documented Taipei Station fixture for that exact query and empty results otherwise. It does not synthesize places for arbitrary text. The Google stub maps a small allowlist of tokens to purchased/pending/revoked/unavailable. Unknown tokens are **verification unavailable**, never owned and never treated as revoked.

## Scripts

```bash
npm install
npm run format:check
npm run lint
npm run typecheck
npm test
npm run dev
```

Tests use `@cloudflare/vitest-pool-workers` and the SQLite Durable Object implementations. A live Cloudflare account is not required.

## Secrets

Copy `.dev.vars.example` to `.dev.vars` and fill values locally. Do not commit secrets. Worker secret names:

- `GEOAPIFY_API_KEY`
- `PLAY_SERVICE_ACCOUNT_EMAIL`
- `PLAY_SERVICE_ACCOUNT_PRIVATE_KEY`
- `RECEIPT_SIGNING_PRIVATE_KEY`
- `RECEIPT_SIGNING_KID`
- `RECEIPT_VERIFY_PUBLIC_KEYS_JSON`
- `PURCHASE_TOKEN_ENCRYPTION_KEY`
- `RATE_KEY_HMAC_SECRET`

## Durable Objects

- `GeoBudget`: rolling 24h credit reservations, rate keys (HMAC only), in-flight limits.
- `Purchase`: token HMAC identity, encrypted token, grant/ack state, alarm retries.

See [../docs/DURABLE_OBJECT_FREE_PROTOTYPE.md](../docs/DURABLE_OBJECT_FREE_PROTOTYPE.md) and [../docs/OWNER_SETUP_CHECKLIST.md](../docs/OWNER_SETUP_CHECKLIST.md).
