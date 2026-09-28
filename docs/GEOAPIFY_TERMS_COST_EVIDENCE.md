# Geoapify terms and routing-cost evidence (B01 / B02)

- Spec version: 1.0
- Checked: 2026-09-28 against spec sources [S1], [S5], [S15]
- Status: **B01 unresolved**. **B02 owner confirmation required**.
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

## B02 — commercial use, attribution, cache, and saved routes

Public terms allow commercial use of the free plan with required attribution. Before production enablement the owner must confirm, in writing against the current Geoapify terms:

1. Commercial use of geocoding and routing for this app.
2. Exact attribution labels and allowlisted URLs (current fixtures use Geoapify and OpenStreetMap copyright).
3. Device-memory search/route cache (5 minutes fresh / 24 hours stale) is permitted.
4. Permanent local saved-route storage and GPX export of provider geometry is permitted. Technical ability to save is not a license.

Until B02 is confirmed, production provider enablement stays off. Existing local perfect-shape routes, imported GPX, and already saved Room routes remain playable.

## Operational reminder

Gateway counting is conservative and durable. It is not the provider invoice. Confirm the Geoapify account has no automatic paid top-up before any live key is installed.
