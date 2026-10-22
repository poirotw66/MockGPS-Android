# Owner setup checklist (B01–B12)

Missing owner secrets and accounts must not block implementable code. Do not paste secrets into git. Production feature flags stay false until the matching gate is closed.

| Gate | Owner input | Blocks | Repo status |
|---|---|---|---|
| B01 | Geoapify hard cap or official worst-case routing credit contract; confirm Free has no auto-pay | Production road routing / full-feature launch | **Open as a proof.** Owner accepted a soft quota and a self-imposed ledger: 25 credits per route (`OWNER_ACCEPTED_SOFT_QUOTA_ROUTE_25`), 2,700 credits / 24h, at most 2 requests per automatic journey. This is not a provider hard cap. Live routing is deployed at `https://bloomwalk-gps-gateway.poirotw66.workers.dev` with search, ads, and purchases still off. |
| B02 | Commercial, attribution, cache, and permanent save/GPX rights | Provider production enablement | **Partial.** Accept in-quota commercial use, current Geoapify + OSM attribution, and device-only cache. GPX/save is on-device user data, not a republication license. Live routing still blocked by B01. |
| B03 | Cloudflare Free account, DO SQLite namespace, free-tier soak | Gateway release | Worker `bloomwalk-gps-gateway` is deployed on the free `workers.dev` hostname. Free-tier soak is still open. |
| B04 | Geoapify API key in Worker secrets | Live geo validation | `GEOAPIFY_API_KEY` is set on the Worker. The value stays out of git. |
| B05 | Play app, `remove_ads_lifetime`, base price, regions, license testers | Live purchase launch | Product ID wired. Price comes from ProductDetails. |
| B06 | Play Developer API + least-privilege service account | Server purchase verification | Live lookup uses `purchases.productsv2` `getproductpurchasev2`; ack stays `products.acknowledge`. Fail-closed without secrets (401/403/404/timeout/5xx ≠ revoked). Missing credentials return unavailable, not owned. |
| B07 | AdMob app/ad unit, app-ads.txt crawler, readiness | Real ads | Test IDs in debug. `site/app-ads.txt` is a placeholder. `adsEnabled` false. |
| B08 | Public privacy/support URLs, support email, Data Safety, ads declaration | Store review | Privacy/support URLs published on Cloudflare Pages (`bloomwalk-gps.pages.dev`; zh-Hant for Play Console). Support email `justin@bloss0m.com`. Data Safety form and ads declaration still pending. |
| B09 | FGS/location declaration, demo video, developer-options instructions | Store review | Manifest and setup guide exist. Video/console form are owner work. |
| B10 | Release signing, device matrix, 16 KB, pre-launch report | Release readiness | `validateReleaseConfig` rejects upload-unsafe config. Unsigned CI may still assemble. Release packaging is 64-bit only (`arm64-v8a`, `x86_64`); debug keeps default ABIs for emulators. Prior unsigned AAB: 64-bit LOAD 16384; 32-bit MapLibre 4096 and now omitted from release. Not a signed Play artifact. |
| B11 | 12 testers / 14-day closed testing if required | Production rollout | Not started. |
| B12 | Audience, ratings, ads content, children-audience honesty | Store review / ads settings | Not started. |

## Public Android build config

Set via Gradle properties or environment:

- `BLOOMWALK_GATEWAY_BASE_URL`
- `BLOOMWALK_ADMOB_APP_ID`
- `BLOOMWALK_ADMOB_BANNER_UNIT_ID`
- `BLOOMWALK_PRIVACY_POLICY_URL` (default: `https://bloomwalk-gps.pages.dev/privacy/zh-Hant/`)
- `BLOOMWALK_SUPPORT_URL` (default: `https://bloomwalk-gps.pages.dev/support/zh-Hant/`)
- `BLOOMWALK_RECEIPT_PUBLIC_KEYS_JSON`

`BILLING_PRODUCT_ID` is fixed to `remove_ads_lifetime`.

## Worker secrets (never in APK or git)

- `GEOAPIFY_API_KEY`
- `PLAY_SERVICE_ACCOUNT_EMAIL`
- `PLAY_SERVICE_ACCOUNT_PRIVATE_KEY`
- `RECEIPT_SIGNING_PRIVATE_KEY`
- `PURCHASE_TOKEN_ENCRYPTION_KEY`
- `RATE_KEY_HMAC_SECRET`
- `OWNER_INSTALL_IDS` — comma-separated install ids that skip the per-install 24h route/search count and the per-install minimum interval. The shared 2,700-credit daily budget still applies. Do not commit the ids.

## Upload builds

`BLOOMWALK_RELEASE_UPLOAD=true ./gradlew validateReleaseConfig` rejects `.invalid` URLs, empty config, AdMob test IDs, and debug signing. Ordinary CI without that flag prints `UNSIGNED_NOT_UPLOADABLE` and still assembles.
