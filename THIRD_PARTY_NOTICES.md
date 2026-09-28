# Third-party notices

- AndroidX, Kotlin, Jetpack Compose, Room, and DataStore are Apache-2.0 licensed.
- MapLibre and OpenStreetMap-derived services are used according to their applicable licenses and attribution requirements.
- OpenFreeMap tiles, the BloomWalk gateway, Geoapify search/routing, Google Play Billing, and AdMob/UMP are external services, not bundled dependencies and not covered by an availability SLA. Production composition does not call public Nominatim or FOSSGIS demo endpoints.

Run `./gradlew :app:dependencies` when preparing a release to review the resolved dependency graph and retain corresponding license notices.
