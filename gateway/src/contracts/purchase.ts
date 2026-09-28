export const PURCHASE_STATUSES = ["owned", "pending", "revoked"] as const;
export type PurchaseStatus = (typeof PURCHASE_STATUSES)[number];

export interface PurchaseVerifyRequest {
  purchaseToken: string;
  installId: string;
}

export interface PurchaseVerifyResponse {
  status: PurchaseStatus;
  receipt?: string;
  acknowledgementPending: boolean;
  serverTime: string;
}

export interface ReceiptClaims {
  iss: string;
  aud: string;
  productId: string;
  installId: string;
  verifiedAt: string;
  receiptVersion: number;
}

export const ALLOWED_PACKAGE_NAME = "com.bloss0m.bloomwalk";
export const ALLOWED_PRODUCT_ID = "remove_ads_lifetime";
export const RECEIPT_VERSION = 1;
