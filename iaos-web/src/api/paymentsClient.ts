import { getTxsByEvents } from "@/api/chainRestClient";
import { DENOM, REST_URL_FOR_BROWSER } from "@/config/chain";
import {
  PAYMENT_MEMO_PREFIX_V1,
  PAYMENT_REQUEST_PATHS_V1,
  type PaymentCheckResult,
  type PaymentConfidence,
  type PaymentRequest,
  type PaymentStatus,
} from "@/contracts/payments";

type Result<T> = { ok: true; data: T } | { ok: false; error: string; supported?: boolean };

const PAYMENT_REQUESTS_STORAGE_KEY = "aios_payment_requests";

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

function createId(prefix: string): string {
  const cryptoObj = typeof crypto !== "undefined" ? crypto : null;
  if (cryptoObj?.randomUUID) return `${prefix}_${cryptoObj.randomUUID()}`;
  return `${prefix}_${Math.random().toString(16).slice(2)}${Date.now().toString(16)}`;
}

function safeGetRaw(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(PAYMENT_REQUESTS_STORAGE_KEY);
}

function safeSetRaw(value: string) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(PAYMENT_REQUESTS_STORAGE_KEY, value);
}

function isPaymentRequest(value: unknown): value is PaymentRequest {
  if (!value || typeof value !== "object") return false;
  const r = value as Record<string, unknown>;
  return (
    typeof r.id === "string" &&
    typeof r.merchantId === "string" &&
    typeof r.ownerEmail === "string" &&
    typeof r.amount === "string" &&
    typeof r.denom === "string" &&
    typeof r.memo === "string" &&
    (r.status === "pending" || r.status === "paid" || r.status === "expired") &&
    typeof r.createdAt === "number" &&
    typeof r.expiresAt === "number"
  );
}

function loadLocalRequests(): PaymentRequest[] {
  const raw = safeGetRaw();
  if (!raw) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isPaymentRequest).map((r) => ({ ...r, ownerEmail: normalizeEmail(r.ownerEmail) }));
  } catch {
    return [];
  }
}

function saveLocalRequests(requests: PaymentRequest[]) {
  safeSetRaw(JSON.stringify(requests));
}

export function getLocalPaymentRequestById(id: string): PaymentRequest | null {
  const trimmed = id.trim();
  if (!trimmed) return null;
  const found = loadLocalRequests().find((r) => r.id === trimmed) ?? null;
  if (!found) return null;
  const normalized = normalizeStatus(found);
  if (normalized !== found) updateLocalPaymentRequest(normalized);
  return normalized;
}

export function listLocalPaymentRequestsByMerchant(merchantId: string, ownerEmail?: string): PaymentRequest[] {
  const trimmed = merchantId.trim();
  if (!trimmed) return [];
  const normalizedOwner = ownerEmail ? normalizeEmail(ownerEmail) : null;
  return loadLocalRequests()
    .filter((r) => r.merchantId === trimmed && (!normalizedOwner || normalizeEmail(r.ownerEmail) === normalizedOwner))
    .sort((a, b) => b.createdAt - a.createdAt);
}

export function normalizeStatus(request: PaymentRequest, now = Date.now()): PaymentRequest {
  const expiresAt = Number.isFinite(request.expiresAt) && request.expiresAt > 0 ? request.expiresAt : request.createdAt + 3600 * 1000;
  if (request.status !== "paid" && now > expiresAt) {
    return { ...request, expiresAt, status: "expired" };
  }
  if (expiresAt !== request.expiresAt) return { ...request, expiresAt };
  return request;
}

function normalizeAndPersistLocalRequests(requests: PaymentRequest[]): PaymentRequest[] {
  let changed = false;
  const now = Date.now();
  const normalized = requests.map((r) => {
    const next = normalizeStatus(r, now);
    if (next !== r) changed = true;
    return next;
  });
  if (changed) saveLocalRequests(normalized);
  return normalized;
}

export function createLocalPaymentRequest(input: {
  merchantId: string;
  ownerEmail: string;
  amount: string;
  denom?: string;
  description?: string;
  expiresInSeconds: number;
}): PaymentRequest {
  const now = Date.now();
  const id = createId("pr");
  const denom = input.denom?.trim() || DENOM;
  const memoBase = `${PAYMENT_MEMO_PREFIX_V1}${id}`;
  const description = (input.description ?? "").trim();
  const memo = description ? `${memoBase} ${description}` : memoBase;

  const request: PaymentRequest = {
    id,
    merchantId: input.merchantId.trim(),
    ownerEmail: normalizeEmail(input.ownerEmail),
    amount: input.amount.trim(),
    denom,
    memo,
    status: "pending",
    createdAt: now,
    expiresAt: now + Math.max(60, Number(input.expiresInSeconds) || 3600) * 1000,
  };

  const all = loadLocalRequests();
  all.push(request);
  saveLocalRequests(all);
  return request;
}

export function updateLocalPaymentRequest(request: PaymentRequest): PaymentRequest {
  const all = loadLocalRequests();
  const filtered = all.filter((r) => r.id !== request.id);
  filtered.push(normalizeStatus(request));
  saveLocalRequests(filtered);
  return getLocalPaymentRequestById(request.id) ?? request;
}

async function tryListOnChain(merchantId: string): Promise<Result<PaymentRequest[]>> {
  const baseUrl = REST_URL_FOR_BROWSER.replace(/\/+$/, "");
  const paths = PAYMENT_REQUEST_PATHS_V1.map((template) =>
    template.replace("{merchantId}", encodeURIComponent(merchantId)),
  );

  for (const path of paths) {
    try {
      const response = await fetch(`${baseUrl}${path}`, { headers: { Accept: "application/json" } });
      if (response.status === 404 || response.status === 501) continue;
      if (!response.ok) continue;

      const json = (await response.json()) as any;
      const items = (json?.requests ?? json?.payment_requests ?? json?.items) as any[];
      if (!Array.isArray(items)) return { ok: false, error: "Formato de resposta inesperado.", supported: true };

      const mapped: PaymentRequest[] = items.flatMap((it) => {
        const createdAt = typeof it.created_at === "string" ? Date.parse(it.created_at) : Number(it.createdAt);
        const expiresAt = typeof it.expires_at === "string" ? Date.parse(it.expires_at) : Number(it.expiresAt);
        const rawStatus = String(it.status ?? "pending");
        const status: PaymentStatus =
          rawStatus === "paid" || rawStatus === "expired" ? rawStatus : "pending";

        if (!it.id || !(it.merchant_id ?? it.merchantId) || !it.amount) return [];

        return [
          {
            id: String(it.id),
            merchantId: String(it.merchant_id ?? it.merchantId),
            ownerEmail: normalizeEmail(String(it.owner_email ?? it.ownerEmail ?? "")),
            amount: String(it.amount),
            denom: String(it.denom ?? DENOM),
            memo: String(it.memo ?? ""),
            status,
            createdAt: Number.isFinite(createdAt) ? createdAt : Date.now(),
            expiresAt: Number.isFinite(expiresAt) ? expiresAt : Date.now(),
            paidTxHash: it.paid_tx_hash ? String(it.paid_tx_hash) : undefined,
          },
        ];
      });

      return { ok: true, data: mapped };
    } catch {
      // fallthrough
    }
  }

  return { ok: false, supported: false, error: "Endpoint on-chain não disponível." };
}

export async function listPaymentRequestsByMerchant(merchantId: string): Promise<PaymentRequest[]> {
  const onChain = await tryListOnChain(merchantId);
  if (onChain.ok) return onChain.data;
  return normalizeAndPersistLocalRequests(listLocalPaymentRequestsByMerchant(merchantId));
}

export async function checkPaymentStatus(input: {
  request: PaymentRequest;
  merchantAddress?: string;
}): Promise<PaymentCheckResult> {
  const now = Date.now();
  const existing = normalizeStatus(getLocalPaymentRequestById(input.request.id) ?? input.request, now);

  if (existing.status === "paid") return { status: "paid", confidence: "high", matchedTxHash: existing.paidTxHash, updated: existing };
  if (existing.expiresAt <= now) {
    const updated = updateLocalPaymentRequest({ ...existing, status: "expired" });
    return { status: "expired", confidence: "unknown", updated };
  }

  const merchantAddress = input.merchantAddress?.trim();
  if (!merchantAddress) return { status: existing.status, confidence: "unknown", updated: existing };

  const amountEvent = `${existing.amount}${existing.denom}`;
  const result = await getTxsByEvents(
    [`transfer.recipient='${merchantAddress}'`, `transfer.amount='${amountEvent}'`],
    25,
  );

  if (!result.ok) return { status: existing.status, confidence: "unknown", updated: existing };

  const txs: any[] = Array.isArray(result.data?.tx_responses) ? result.data.tx_responses : [];
  const memoNeedle = `${PAYMENT_MEMO_PREFIX_V1}${existing.id}`;
  const memoMatch = txs.find((t) => {
    if (t?.code !== 0) return false;
    const memo = t?.tx?.body?.memo ?? "";
    if (typeof memo !== "string") return false;
    return memo.includes(memoNeedle) || (existing.memo && memo.includes(existing.memo));
  });

  if (memoMatch?.txhash) {
    const matchedTxHash = String(memoMatch.txhash);
    const updated = updateLocalPaymentRequest({ ...existing, status: "paid", paidTxHash: matchedTxHash });
    return { status: "paid", confidence: "high", matchedTxHash, updated };
  }

  const anyAmountMatch = txs.find((t) => t?.code === 0 && typeof t?.txhash === "string");
  if (anyAmountMatch?.txhash) {
    return { status: existing.status, confidence: "medium", matchedTxHash: String(anyAmountMatch.txhash), updated: existing };
  }

  return { status: existing.status, confidence: "unknown", updated: existing };
}
