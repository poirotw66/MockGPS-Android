# Privacy and Data Safety

BloomWalk GPS stores favorites, route history, app settings, and the last successfully active coordinate only on the device. It does not include analytics, advertising SDKs, accounts, or a backend.

When the user explicitly plans a route, ordered route coordinates are sent to the configured FOSSGIS-compatible OSRM endpoint. When the user types a place search, the query is sent to the configured OpenStreetMap-compatible search endpoint; public endpoints are rate-limited to one request per second, are unavailable offline, and have no SLA.

Before publishing, the Play Data Safety form must accurately declare optional precise location used for the core feature, locally stored favorites/routes/settings, and the above user-initiated search and route-planning network transfers. This document is an engineering inventory, not a legal determination or a claim that the Play Console form has been submitted. Do not add a signing key, API key, or provider token to this repository.
