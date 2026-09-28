declare namespace Cloudflare {
  interface Env {
    GEO_BUDGET: DurableObjectNamespace;
    PURCHASE: DurableObjectNamespace;
    ENVIRONMENT: string;
    SEARCH_ENABLED: string;
    ROUTING_ENABLED: string;
    PURCHASES_ENABLED: string;
    ADS_ENABLED: string;
    PRIVACY_POLICY_URL: string;
    SUPPORT_URL: string;
    GEO_LEDGER_ID: string;
    GEO_PROVIDER_MODE: string;
    PLAY_API_MODE: string;
    ALLOW_EMPTY_LEDGER_INIT: string;
    GEO_CREDIT_BUDGET_24H: string;
    GEO_GLOBAL_MIN_INTERVAL_MS: string;
    CLIENT_SEARCH_LIMIT_24H: string;
    CLIENT_ROUTE_LIMIT_24H: string;
    CLIENT_MIN_INTERVAL_MS: string;
    IP_REQUEST_LIMIT_PER_MINUTE: string;
    PROVIDER_TIMEOUT_MS: string;
    MAX_IN_FLIGHT_GEO: string;
    CLOCK_MARGIN_MS: string;
    BILLING_POLICY_JSON: string;
    RECEIPT_ISSUER: string;
    RECEIPT_AUDIENCE: string;
    ALLOWED_PACKAGE_NAME: string;
    ALLOWED_PRODUCT_ID: string;
    TEST_NOW_MS?: string;
    GEOAPIFY_API_KEY?: string;
    PLAY_SERVICE_ACCOUNT_EMAIL?: string;
    PLAY_SERVICE_ACCOUNT_PRIVATE_KEY?: string;
    RECEIPT_SIGNING_PRIVATE_KEY?: string;
    RECEIPT_SIGNING_KID?: string;
    RECEIPT_VERIFY_PUBLIC_KEYS_JSON?: string;
    PURCHASE_TOKEN_ENCRYPTION_KEY?: string;
    RATE_KEY_HMAC_SECRET?: string;
    GOOGLE_STUB_TOKENS_JSON?: string;
  }
}

type Env = Cloudflare.Env;
