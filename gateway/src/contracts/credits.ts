export type GeoOperation = "search" | "route";

export interface BillingPolicy {
  version: string;
  evidence: string;
  documentedMaximumCredits?: Partial<Record<GeoOperation, number>>;
}

export interface ValidatedGeoRequest {
  operation: GeoOperation;
}

export type CreditUpperBound = {
  kind: "bounded";
  credits: number;
  policyVersion: string;
};

export type Unsupported = {
  kind: "unsupported";
  reason: string;
};

export type CreditEstimate = CreditUpperBound | Unsupported;
