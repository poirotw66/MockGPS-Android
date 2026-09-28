import path from "node:path";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";
import {
  TEST_RECEIPT_PRIVATE_KEY,
  TEST_RECEIPT_PUBLIC_KEY,
  TEST_RECEIPT_PUBLIC_KEY_2,
} from "./test/test-keys.ts";

const verifyKeys = JSON.stringify([
  { kid: "test-kid-1", publicKeyPem: TEST_RECEIPT_PUBLIC_KEY },
  { kid: "test-kid-2", publicKeyPem: TEST_RECEIPT_PUBLIC_KEY_2 },
]);

const stubTokens = JSON.stringify({
  "purchased-token-001": { state: "PURCHASED", acknowledged: false },
  "purchased-ack-fail-001": { state: "PURCHASED", acknowledged: false, ackFails: true },
  "pending-token-001": { state: "PENDING", acknowledged: false },
  "revoked-token-001": { state: "REVOKED", acknowledged: false },
  "unavailable-token-001": { state: "UNAVAILABLE" },
});

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: path.join(import.meta.dirname, "wrangler.jsonc") },
      miniflare: {
        bindings: {
          ENVIRONMENT: "test",
          SEARCH_ENABLED: "true",
          ROUTING_ENABLED: "true",
          PURCHASES_ENABLED: "true",
          ADS_ENABLED: "false",
          PRIVACY_POLICY_URL: "https://example.invalid/privacy",
          SUPPORT_URL: "https://example.invalid/support",
          ALLOW_EMPTY_LEDGER_INIT: "true",
          GEO_PROVIDER_MODE: "stub",
          PLAY_API_MODE: "stub",
          RATE_KEY_HMAC_SECRET: "test-rate-key-hmac-secret-not-production",
          PURCHASE_TOKEN_ENCRYPTION_KEY:
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          RECEIPT_SIGNING_PRIVATE_KEY: TEST_RECEIPT_PRIVATE_KEY,
          RECEIPT_SIGNING_KID: "test-kid-1",
          RECEIPT_VERIFY_PUBLIC_KEYS_JSON: verifyKeys,
          GOOGLE_STUB_TOKENS_JSON: stubTokens,
          GEOAPIFY_API_KEY: "test-api-key-should-never-appear",
        },
      },
    }),
  ],
});
