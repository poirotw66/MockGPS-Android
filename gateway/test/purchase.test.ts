import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import {
  buildReceiptClaims,
  parseVerifyKeys,
  signReceipt,
  verifyReceipt,
} from "../src/purchase-verifier";
import type { GatewayErrorBody, PurchaseVerifyResponse } from "../src/contracts/index";
import {
  jsonOf,
  purchase,
  purchaseDo,
  TEST_RECEIPT_PRIVATE_KEY,
  TEST_RECEIPT_PRIVATE_KEY_2,
  TEST_RECEIPT_PUBLIC_KEY,
  TEST_RECEIPT_PUBLIC_KEY_2,
  uuid,
} from "./helpers";

const keys = [
  { kid: "test-kid-1", publicKeyPem: TEST_RECEIPT_PUBLIC_KEY },
  { kid: "test-kid-2", publicKeyPem: TEST_RECEIPT_PUBLIC_KEY_2 },
];

describe("purchase receipts", () => {
  it("signs and verifies a receipt, then rejects wrong kid or tampered claims", async () => {
    const claims = buildReceiptClaims({
      issuer: "bloomwalk-gps-gateway",
      audience: "com.bloss0m.bloomwalk",
      installId: "11111111-1111-4111-8111-111111111111",
      verifiedAt: "2026-09-28T00:00:00.000Z",
    });
    const receipt = await signReceipt({
      privateKeyPem: TEST_RECEIPT_PRIVATE_KEY,
      kid: "test-kid-1",
      claims,
    });
    await expect(verifyReceipt(receipt, keys)).resolves.toMatchObject(claims);

    const [header, payload, signature] = receipt.split(".");
    const tamperedPayload = payload ? payload.replace(/[A-Za-z]/, "A") : "";
    await expect(
      verifyReceipt(`${header}.${tamperedPayload}.${signature}`, keys),
    ).rejects.toBeTruthy();

    const wrongKid = await signReceipt({
      privateKeyPem: TEST_RECEIPT_PRIVATE_KEY,
      kid: "unknown-kid",
      claims,
    });
    await expect(verifyReceipt(wrongKid, keys)).rejects.toBeTruthy();
  });

  it("keeps old verification keys after rotation", async () => {
    const claims = buildReceiptClaims({
      issuer: "bloomwalk-gps-gateway",
      audience: "com.bloss0m.bloomwalk",
      installId: "11111111-1111-4111-8111-111111111111",
      verifiedAt: "2026-09-28T00:00:00.000Z",
    });
    const oldReceipt = await signReceipt({
      privateKeyPem: TEST_RECEIPT_PRIVATE_KEY,
      kid: "test-kid-1",
      claims,
    });
    const newReceipt = await signReceipt({
      privateKeyPem: TEST_RECEIPT_PRIVATE_KEY_2,
      kid: "test-kid-2",
      claims,
    });
    const rotated = parseVerifyKeys(JSON.stringify(keys));
    await expect(verifyReceipt(oldReceipt, rotated)).resolves.toMatchObject(claims);
    await expect(verifyReceipt(newReceipt, rotated)).resolves.toMatchObject(claims);
  });
});

describe("POST /v1/purchases/verify", () => {
  it("returns owned with a receipt for a purchased stub token", async () => {
    const installId = uuid();
    const response = await purchase(
      undefined,
      { purchaseToken: "purchased-token-001", installId },
      {
        installId,
      },
    );
    expect(response.status).toBe(200);
    const body = await jsonOf<PurchaseVerifyResponse>(response);
    expect(body.status).toBe("owned");
    expect(body.receipt).toBeTypeOf("string");
    expect(body.receipt).not.toContain("purchased-token-001");
    const claims = await verifyReceipt(body.receipt ?? "", keys);
    expect(claims.productId).toBe("remove_ads_lifetime");
    expect(claims.installId).toBe(installId);
    expect(claims.iss).toBe("bloomwalk-gps-gateway");
  });

  it("maps pending and revoked without treating unknown tokens as revoked", async () => {
    const pendingId = uuid();
    const pending = await purchase(
      undefined,
      { purchaseToken: "pending-token-001", installId: pendingId },
      {
        installId: pendingId,
      },
    );
    expect((await jsonOf<PurchaseVerifyResponse>(pending)).status).toBe("pending");

    const revokedId = uuid();
    const revoked = await purchase(
      undefined,
      { purchaseToken: "revoked-token-001", installId: revokedId },
      {
        installId: revokedId,
      },
    );
    expect((await jsonOf<PurchaseVerifyResponse>(revoked)).status).toBe("revoked");

    const unknownId = uuid();
    const unknown = await purchase(
      undefined,
      { purchaseToken: "no-such-token", installId: unknownId },
      {
        installId: unknownId,
      },
    );
    expect((await jsonOf<GatewayErrorBody>(unknown)).error.code).toBe("PROVIDER_UNAVAILABLE");
  });

  it("coalesces the same token and retries acknowledgement via alarm", async () => {
    const installId = uuid();
    const first = await purchase(
      undefined,
      { purchaseToken: "purchased-ack-fail-001", installId },
      {
        installId,
      },
    );
    const second = await purchase(
      undefined,
      { purchaseToken: "purchased-ack-fail-001", installId },
      {
        installId,
      },
    );
    const firstBody = await jsonOf<PurchaseVerifyResponse>(first);
    expect(firstBody.status).toBe("owned");
    expect(firstBody.acknowledgementPending).toBe(true);
    expect((await jsonOf<PurchaseVerifyResponse>(second)).status).toBe("owned");
    const stub = purchaseDo();
    const inspect = await runInDurableObject(stub, async (instance) => {
      await instance.setNow(Date.now() + 60_000);
      await instance.alarm();
      return instance.inspect();
    });
    expect(inspect.grantCount).toBeGreaterThan(0);
    expect(inspect.pendingAck).toBeGreaterThan(0);
    expect(inspect.lastAlarmAt).not.toBeNull();
  });

  it("retries acknowledgement from durable SQL after a later alarm, then alarms the 48h deadline", async () => {
    const installId = uuid();
    const response = await purchase(
      undefined,
      { purchaseToken: "purchased-ack-fail-001", installId },
      { installId },
    );
    expect((await jsonOf<PurchaseVerifyResponse>(response)).acknowledgementPending).toBe(true);
    const stub = purchaseDo();
    const first = await runInDurableObject(stub, async (instance) => {
      const before = await instance.inspect();
      const dueAt = before.nextAckAt ?? Date.now() + 60_000;
      await instance.setNow(dueAt);
      await instance.alarm();
      return instance.inspect();
    });
    expect(first.pendingAck).toBeGreaterThan(0);
    expect(first.pendingAttempts).toBeGreaterThan(0);
    expect(first.nextAckAt).not.toBeNull();

    const restarted = await runInDurableObject(stub, async (instance) => {
      const dueAt = first.nextAckAt ?? Date.now() + 5 * 60_000;
      await instance.setNow(dueAt);
      await instance.alarm();
      return instance.inspect();
    });
    expect(restarted.pendingAck).toBeGreaterThan(0);
    expect(restarted.pendingAttempts).toBeGreaterThanOrEqual(first.pendingAttempts);
    expect(restarted.lastAlarmAt).not.toBeNull();

    const expired = await runInDurableObject(stub, async (instance) => {
      await instance.setNow(Date.now() + 48 * 60 * 60_000);
      await instance.alarm();
      return instance.inspect();
    });
    expect(expired.deadlineExceeded).toBe(true);
  });

  it("keeps acknowledgement idempotent across repeated alarms after success", async () => {
    const installId = uuid();
    const response = await purchase(
      undefined,
      { purchaseToken: "purchased-token-001", installId },
      { installId },
    );
    expect((await jsonOf<PurchaseVerifyResponse>(response)).status).toBe("owned");
    const stub = purchaseDo();
    const before = await runInDurableObject(stub, async (instance) => instance.inspect());
    const inspect = await runInDurableObject(stub, async (instance) => {
      for (let index = 0; index < 8; index += 1) {
        await instance.setNow(Date.now() + (index + 1) * 60_000);
        await instance.alarm();
      }
      return instance.inspect();
    });
    expect(inspect.grantCount).toBeGreaterThan(0);
    expect(inspect.ackedCount).toBeGreaterThanOrEqual(before.ackedCount);
    expect(inspect.ackedCount).toBeGreaterThan(0);
  });

  it("does not return owned when durable grant persistence fails", async () => {
    const stub = purchaseDo();
    await stub.failNextTransaction();
    const installId = uuid();
    const response = await purchase(
      undefined,
      { purchaseToken: "purchased-token-001", installId },
      {
        installId,
      },
    );
    expect((await jsonOf<GatewayErrorBody>(response)).error.code).toBe("PROVIDER_UNAVAILABLE");
  });
});
