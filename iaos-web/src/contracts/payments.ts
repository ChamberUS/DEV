// v1 contract — tipos estáveis para pagamentos IAOS ↔ BYX

export type PaymentStatus = "pending" | "paid" | "expired";
export type PaymentConfidence = "high" | "medium" | "unknown";

export type PaymentRequest = {
  id: string;
  merchantId: string;
  ownerEmail: string;
  amount: string; // base units (ex.: ubyx)
  denom: string;
  memo: string;
  status: PaymentStatus;
  createdAt: number;
  expiresAt: number;
  paidTxHash?: string;
};

export type PaymentCheckResult = {
  status: PaymentStatus;
  confidence: PaymentConfidence;
  matchedTxHash?: string;
  updated: PaymentRequest;
};

export const PAYMENT_CONTRACT_VERSION = "v1";
export const PAYMENT_MEMO_PREFIX_V1 = "aios:";
export const PAYMENT_REQUEST_PATHS_V1 = [
  "/byx/payments/v1/payment_requests?merchant_id={merchantId}",
  "/byx/payments/v1/requests?merchant_id={merchantId}",
  "/byx/payments/v1/merchants/{merchantId}/requests",
];
