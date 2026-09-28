# GeoBudget Durable Object Free prototype

The gateway uses one named SQLite Durable Object class `GeoBudget` per environment plus a separate `Purchase` class. This is the Free-plan prototype required by P0.

## Mapping to Workers Free

| Limit | Prototype rule |
|---|---|
| 100,000 Worker requests/day | Accept pause at the platform cap. No paid burst. |
| 100,000 DO requests/day | One DO invocation per admitted geo operation plus finalize. |
| 13,000 GB-s/day | Transactions stay short; no await of provider fetch inside the DO write transaction. |
| 5 million rows read / 100,000 rows written / 5 GB | Rolling 24h ledger with bounded batch delete. Cleanup target ≤ 48h. |

These limits are independent. Do not add them together as a single capacity number.

## Reservation semantics exercised by tests

1. Validate request and feature flags in the Worker.
2. In one DO transaction, check client/global budgets, in-flight count, and earliest dispatch time, then insert a durable reservation.
3. The transaction does not include the Geoapify fetch.
4. Dispatch immediately or return 429. No unbounded queue.
5. Timeout, cancel, restart, parse failure, and unknown provider errors keep the reservation until the window expires.
6. Duplicate request IDs store only an HMAC digest.

`estimateMaximumCredits` returns `Unsupported` for routing until B01 is resolved, so production routing cannot dispatch.

Paid plans, automatic top-up, and extra Cloudflare products are out of scope.
