# Geoapify terms and routing-cost evidence (B01 / B02)

- Spec version: 1.0
- Checked: 2026-10-21 against [pricing](https://www.geoapify.com/pricing/), [pricing details](https://www.geoapify.com/pricing-details/), [routing API](https://www.geoapify.com/routing-api/), and [terms v5 (2 Feb 2024)](https://www.geoapify.com/terms-and-conditions/)
- Status: **B01 remains open**. **B02 accepted only for in-quota commercial use, required attribution, and on-device cache**. Production routing stays off.
- Owner choices recorded 2026-10-21: no automatic paid top-up; owner wants a hard stop rather than an upgrade. Public pricing FAQ still calls daily limits soft.
- This note is implementation evidence, not a license grant and not a production enablement.

## B01 — routing credit upper bound

Observed from Geoapify public pricing and routing docs:

- Free plan lists 3,000 credits/day and up to 5 requests/second. The quota is a **soft** account-level limit, not a proven hard cap that blocks excess dispatch for every key, console test, timeout, or in-flight request.
- Geocoding is commonly 1 credit per request. Routing credit depends on waypoints, distance, and options. The published formulas/examples are **not** a complete worst-case bound for every legal 2–25 waypoint request in this app.
- **Forbidden shortcuts:** `waypoints.size - 1` and haversine input distance are not a full routing-cost upper bound. Distance is only known after the request completes, so a later rejection does not prove the provider did not bill.

Implemented interface:

- `estimateMaximumCredits(validatedRequest, billingPolicy): CreditUpperBound | Unsupported`
- Search may reserve 1 credit when the billing policy version records that geocoding is 1 credit.
- Routing without a versioned, owner-verified `billingPolicy` returns `Unsupported` and **must not dispatch**.
- Production `routingEnabled` remains **false** until one of the spec §6.3 options is satisfied:
  1. Provider/account hard cap covering multi-key, console, failure/timeout, cross-day, and in-flight billing; or
  2. Official max distance/points/billing contract used to derive and test a single-request worst-case reservation.

Do not invent a guessed constant to pretend there is a hard guarantee.

### Owner decision (2026-10-21)

| Item | Best choice | Why |
|---|---|---|
| B01-1 No automatic pay | **Yes** | Stay on Free. Do not attach a card or accept an upgrade email. |
| B01-2 Hard vs soft cap | **Treat as soft** | Owner asked for a hard stop. Geoapify pricing FAQ says limits are soft: over-quota use can lead to an upgrade request, and the account may be blocked if that is ignored. Terms also allow suspension **or** overage charges. That is not a proven hard cap across keys, console tests, timeouts, cross-day windows, or in-flight requests. |
| B01-3 Accept `waypoints - 1` as a worst-case contract | **No** | Published routing cost is `(waypoints - 1)` plus 1 credit per 500 km of the **result** length, and advanced options cost more. Result length is known only after dispatch. A 100 km input chain does not prove the billed distance. |

Live routing is deployed with this reservation. Search stays off. The reservation is still not a provider hard cap.

### Owner-accepted ledger reservation (2026-10-21)

The owner asked to implement a self-imposed budget instead of waiting for a provider hard cap. This does **not** close B01 and is **not** a worst-case proof.

- Policy version: `owner-accepted-route-exposure-v1`
- Evidence token: `OWNER_ACCEPTED_SOFT_QUOTA_ROUTE_25`
- Each admitted route reserves **25** credits: the published partial formula at the app's 25-point cap (`waypoints - 1` = 24) plus one 500 km result bucket. Any other route number stays `Unsupported` and is not dispatched.
- Gateway daily budget stays **2,700** credits. One install stays at **10** route requests per 24 hours.
- One automatic-journey generation sends at most **two** route requests (initial plan and one shrink). Failure keeps the local shape and explains why.
- Deployed Worker: `https://bloomwalk-gps-gateway.poirotw66.workers.dev` with `ROUTING_ENABLED=true` and `GEO_PROVIDER_MODE=live`. Search, ads, and purchases stay off. Confirm the Geoapify account still has no payment method.

## B02 — commercial use, attribution, cache, and saved routes

### Owner decision (2026-10-21)

| Item | Best choice | Basis |
|---|---|---|
| B02-1 Commercial geocoding and routing | **Yes, inside the Free quota only** | Pricing FAQ allows commercial production use within quota, rate limits, feature limits, and attribution. Terms v5 still say Free commercial production has limitations and to contact `info@geoapify.com`. Do not buy a paid plan to remove that caveat. |
| B02-2 Attribution | **Keep the current labels** | `Powered by Geoapify` → `https://www.geoapify.com/` and `© OpenStreetMap contributors` → `https://www.openstreetmap.org/copyright`. Pricing FAQ requires a Geoapify follow-link; terms require OSM attribution and Free-plan Geoapify attribution. |
| B02-3 Device memory cache | **Yes, device memory only** | 5-minute fresh / 24-hour stale, same key, not shared, not logged. Terms do not grant a redistributable database. No edge cache of addresses or routes. |
| B02-4 Permanent save and GPX | **On-device user routes only** | Saving and exporting a route the user stored in this app stays a local user action. This is **not** a license to republish provider geometry as a dataset. Existing Room routes, imports, and perfect-shape playback stay available. |

Live provider enablement still waits on B01. These B02 choices do not turn `routingEnabled` on.

## Operational reminder

Gateway counting is conservative and durable. It is not the provider invoice. Confirm the Geoapify account has no automatic paid top-up before any live key is installed.
