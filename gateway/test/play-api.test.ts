import { describe, expect, it, beforeAll } from "vitest";
import { GatewayError } from "../src/contracts/index";
import {
  acknowledgeLiveGooglePurchase,
  lookupLiveGooglePurchase,
  mapGoogleProductPurchaseV2,
  resetGoogleAccessTokenCache,
} from "../src/play-api";

let testPrivateKeyPem = "";

function productPurchaseV2(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    kind: "androidpublisher#productPurchaseV2",
    purchaseStateContext: { purchaseState: "PURCHASED" },
    acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
    productLineItem: [
      {
        productId: "remove_ads_lifetime",
        productOfferDetails: {
          quantity: 1,
          consumptionState: "CONSUMPTION_STATE_YET_TO_BE_CONSUMED",
        },
      },
    ],
    ...overrides,
  };
}

describe("Google Play ProductPurchaseV2 mapping", () => {
  it("maps purchased, pending, and cancelled without treating unspecified as revoked", () => {
    expect(mapGoogleProductPurchaseV2(productPurchaseV2()).state).toBe("PURCHASED");
    expect(
      mapGoogleProductPurchaseV2(
        productPurchaseV2({
          purchaseStateContext: { purchaseState: "PENDING" },
        }),
      ).state,
    ).toBe("PENDING");
    expect(
      mapGoogleProductPurchaseV2(
        productPurchaseV2({
          purchaseStateContext: { purchaseState: "CANCELLED" },
        }),
      ).state,
    ).toBe("REVOKED");
    expect(() =>
      mapGoogleProductPurchaseV2(
        productPurchaseV2({
          purchaseStateContext: { purchaseState: "PURCHASE_STATE_UNSPECIFIED" },
        }),
      ),
    ).toThrow(GatewayError);
    try {
      mapGoogleProductPurchaseV2(
        productPurchaseV2({
          purchaseStateContext: { purchaseState: "PURCHASE_STATE_UNSPECIFIED" },
        }),
      );
    } catch (error) {
      expect(error).toMatchObject({ code: "PROVIDER_UNAVAILABLE" });
    }
  });

  it("does not map legacy products.get numeric purchaseState as a valid v2 payload", () => {
    expect(() =>
      mapGoogleProductPurchaseV2({
        purchaseState: 0,
        acknowledgementState: 1,
        productId: "remove_ads_lifetime",
        quantity: 1,
      }),
    ).toThrow(GatewayError);
  });

  it("maps acknowledgement from AcknowledgementState enums", () => {
    expect(mapGoogleProductPurchaseV2(productPurchaseV2()).acknowledged).toBe(false);
    expect(
      mapGoogleProductPurchaseV2(
        productPurchaseV2({
          acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
        }),
      ).acknowledged,
    ).toBe(true);
  });

  it("rejects rentals and consumed line items as invalid, not revoked", () => {
    try {
      mapGoogleProductPurchaseV2(
        productPurchaseV2({
          productLineItem: [
            {
              productId: "remove_ads_lifetime",
              productOfferDetails: {
                quantity: 1,
                rentOfferDetails: {},
              },
            },
          ],
        }),
      );
      throw new Error("expected rental mapping to fail");
    } catch (error) {
      expect(error).toMatchObject({ code: "INVALID_REQUEST" });
    }
    try {
      mapGoogleProductPurchaseV2(
        productPurchaseV2({
          productLineItem: [
            {
              productId: "remove_ads_lifetime",
              productOfferDetails: {
                quantity: 1,
                consumptionState: "CONSUMPTION_STATE_CONSUMED",
              },
            },
          ],
        }),
      );
    } catch (error) {
      expect(error).toMatchObject({ code: "INVALID_REQUEST" });
    }
  });

  it("rejects production test purchases and allows them only when opted in", () => {
    const payload = productPurchaseV2({
      testPurchaseContext: { fopType: "TEST" },
    });
    try {
      mapGoogleProductPurchaseV2(payload);
    } catch (error) {
      expect(error).toMatchObject({ code: "PROVIDER_UNAVAILABLE" });
    }
    expect(mapGoogleProductPurchaseV2(payload, { allowTestPurchases: true }).state).toBe(
      "PURCHASED",
    );
  });
});

describe("Google Play live HTTP", () => {
  beforeAll(async () => {
    testPrivateKeyPem = await rsaPem();
  });

  it("treats 401/403/404/timeout/5xx as unavailable rather than revoked", async () => {
    resetGoogleAccessTokenCache();
    for (const status of [401, 403, 404, 500]) {
      const fetchImpl: typeof fetch = async (input) => {
        const url = String(input);
        if (url.includes("oauth2.googleapis.com/token")) {
          return new Response(JSON.stringify({ access_token: "ya29.test", expires_in: 3600 }), {
            status: 200,
          });
        }
        return new Response("unavailable", { status });
      };
      await expect(
        lookupLiveGooglePurchase({
          purchaseToken: "tok",
          serviceAccountEmail: "play@test.invalid",
          serviceAccountPrivateKey: testPrivateKeyPem,
          nowMs: Date.UTC(2026, 8, 28),
          fetchImpl,
        }),
      ).rejects.toMatchObject({ code: "PROVIDER_UNAVAILABLE" });
    }
    resetGoogleAccessTokenCache();
    const timeoutFetch: typeof fetch = async (input) => {
      const url = String(input);
      if (url.includes("oauth2.googleapis.com/token")) {
        return new Response(JSON.stringify({ access_token: "ya29.test", expires_in: 3600 }), {
          status: 200,
        });
      }
      throw new DOMException("The operation was aborted.", "TimeoutError");
    };
    await expect(
      lookupLiveGooglePurchase({
        purchaseToken: "tok",
        serviceAccountEmail: "play@test.invalid",
        serviceAccountPrivateKey: testPrivateKeyPem,
        nowMs: Date.UTC(2026, 8, 28),
        fetchImpl: timeoutFetch,
      }),
    ).rejects.toMatchObject({ code: "PROVIDER_UNAVAILABLE" });
  });

  it("looks up purchases via purchases.productsv2 getproductpurchasev2", async () => {
    resetGoogleAccessTokenCache();
    const fetchImpl: typeof fetch = async (input, init) => {
      const url = String(input);
      if (url.includes("oauth2.googleapis.com/token")) {
        expect(String(init?.body)).not.toContain("PRIVATE KEY");
        return new Response(JSON.stringify({ access_token: "ya29.test", expires_in: 3600 }), {
          status: 200,
        });
      }
      expect(url).toContain("/purchases/productsv2/tokens/owned-token");
      expect(url).not.toContain("/purchases/products/remove_ads_lifetime/tokens/");
      const headers = new Headers(init?.headers);
      expect(headers.get("authorization")).toBe("Bearer ya29.test");
      return new Response(JSON.stringify(productPurchaseV2()), { status: 200 });
    };
    const lookup = await lookupLiveGooglePurchase({
      purchaseToken: "owned-token",
      serviceAccountEmail: "play@test.invalid",
      serviceAccountPrivateKey: testPrivateKeyPem,
      nowMs: Date.UTC(2026, 8, 28),
      fetchImpl,
    });
    expect(lookup.state).toBe("PURCHASED");
    expect(lookup.acknowledged).toBe(false);
    expect(lookup.productId).toBe("remove_ads_lifetime");
    expect(lookup.packageName).toBe("com.bloss0m.bloomwalk");
  });

  it("acks via products.acknowledge, not productsv2", async () => {
    resetGoogleAccessTokenCache();
    let acknowledgedUrl = "";
    const fetchImpl: typeof fetch = async (input) => {
      const url = String(input);
      if (url.includes("oauth2.googleapis.com/token")) {
        return new Response(JSON.stringify({ access_token: "ya29.test", expires_in: 3600 }), {
          status: 200,
        });
      }
      if (url.endsWith(":acknowledge")) {
        acknowledgedUrl = url;
        return new Response(null, { status: 204 });
      }
      return new Response("missing", { status: 404 });
    };
    const result = await acknowledgeLiveGooglePurchase({
      purchaseToken: "owned-token",
      serviceAccountEmail: "play@test.invalid",
      serviceAccountPrivateKey: testPrivateKeyPem,
      nowMs: Date.UTC(2026, 8, 28),
      fetchImpl,
    });
    expect(result).toBe("acked");
    expect(acknowledgedUrl).toContain(
      "/purchases/products/remove_ads_lifetime/tokens/owned-token:acknowledge",
    );
    expect(acknowledgedUrl).not.toContain("productsv2");
  });
});

async function rsaPem(): Promise<string> {
  const pair = (await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
  const pkcs8 = await crypto.subtle.exportKey("pkcs8", pair.privateKey);
  const bytes = new Uint8Array(pkcs8 as ArrayBuffer);
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  const body = btoa(binary)
    .replace(/(.{64})/g, "$1\n")
    .trim();
  return `-----BEGIN PRIVATE KEY-----\n${body}\n-----END PRIVATE KEY-----`;
}
