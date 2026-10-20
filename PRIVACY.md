# Privacy and Data Safety

This is the engineering inventory for BloomWalk GPS. It is not a claim that Play Console forms have been submitted. Public privacy and support pages are live on Cloudflare Pages (`https://bloomwalk-gps.pages.dev/`); Play Console uses the zh-Hant privacy URL. Policy effective date: 2026-10-20. Support contact: justin@bloss0m.com.

## On-device data

Favorites, saved routes, recent locations, app settings, and the last active coordinate stay on the device until the user deletes them or uninstalls the app. Import/export formats are unchanged. Entitlement receipts and purchase tokens are stored in an app-private encrypted file (`entitlement.store`) using Android Keystore. That file is not part of Room, JSON backup, or favorites export.

## Online geography

Typing never sends a search. Only an explicit Search or Plan action may call the BloomWalk HTTPS gateway. The gateway talks to Geoapify. There is no public Nominatim or FOSSGIS demo fallback. Production road routing stays disabled while B01 is unresolved. Map tiles still come from OpenFreeMap independently of gateway quota.

Requests include a random install ID, client version, and request ID. The install ID is not an account and is not an advertising ID.

## Advertising and consent

The free tier may show one anchored adaptive banner after UMP `canRequestAds()` is true, the server `adsEnabled` flag is true, and the layout is eligible. Paid, unknown, pending, and verification states do not load ads. The app does not send mock or real coordinates, search queries, routes, favorites, or purchase tokens to the ads SDK. AdMob/UMP still process their own device and interaction signals; Data Safety must follow the locked SDK versions.

## Purchases

Google Play Billing is used for the one-time product `remove_ads_lifetime`. Purchase tokens are sent over TLS to `POST /v1/purchases/verify`. The gateway verifies against the Play Developer API. Receipts are ES256 JWS and do not contain raw tokens, email, or geography.

## Backend retention

Gateway application logs must not include query text, coordinates, tokens, or API keys. GeoBudget Durable Object stores reservation timestamps, credit amounts, and HMAC request digests for a rolling 24-hour window. Purchase Durable Object stores grant state and an encrypted token only while acknowledgement retry is required. Platform access logs are not the same as application logs and must not be described as “zero retention.”

## What changed from earlier drafts

Previous text that claimed “no advertising SDKs” and “no backend” is obsolete. The app now includes AdMob/UMP, a Cloudflare gateway, Geoapify, OpenFreeMap, and Google Play Billing.
