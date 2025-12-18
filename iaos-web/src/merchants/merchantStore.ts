export type Merchant = {
  id: string;
  ownerEmail: string;
  displayName: string;
  description: string;
  category: string;
  logoUrl?: string;
  byxAddress: string;
  createdAt: number;
  updatedAt: number;
};

const MERCHANTS_STORAGE_KEY = "aios_merchants";

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

function safeGetRaw(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(MERCHANTS_STORAGE_KEY);
}

function safeSetRaw(value: string) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(MERCHANTS_STORAGE_KEY, value);
}

function createId(prefix: string): string {
  const cryptoObj = typeof crypto !== "undefined" ? crypto : null;
  if (cryptoObj?.randomUUID) return `${prefix}_${cryptoObj.randomUUID()}`;
  return `${prefix}_${Math.random().toString(16).slice(2)}${Date.now().toString(16)}`;
}

export function normalizeByxAddress(address: string): string {
  return address.trim();
}

export function isPlausibleByxAddress(address: string): boolean {
  const trimmed = normalizeByxAddress(address);
  if (trimmed.length < 20) return false;
  if (/\s/.test(trimmed)) return false;
  if (!trimmed.includes("1")) return false;
  return true;
}

function isMerchant(value: unknown): value is Merchant {
  if (!value || typeof value !== "object") return false;
  const m = value as Record<string, unknown>;
  return (
    typeof m.id === "string" &&
    typeof m.ownerEmail === "string" &&
    typeof m.displayName === "string" &&
    typeof m.description === "string" &&
    typeof m.category === "string" &&
    typeof m.byxAddress === "string" &&
    typeof m.createdAt === "number" &&
    typeof m.updatedAt === "number"
  );
}

function loadMerchants(): Merchant[] {
  const raw = safeGetRaw();
  if (!raw) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isMerchant).map((m) => ({
      ...m,
      ownerEmail: normalizeEmail(m.ownerEmail),
    }));
  } catch {
    return [];
  }
}

function saveMerchants(merchants: Merchant[]) {
  safeSetRaw(JSON.stringify(merchants));
}

export function listMerchants(): Merchant[] {
  return loadMerchants();
}

export function getMerchantById(id: string): Merchant | null {
  const trimmed = id.trim();
  if (!trimmed) return null;
  return loadMerchants().find((m) => m.id === trimmed) ?? null;
}

export function getMerchantByEmail(email: string): Merchant | null {
  const normalized = normalizeEmail(email);
  if (!normalized) return null;
  return loadMerchants().find((m) => normalizeEmail(m.ownerEmail) === normalized) ?? null;
}

export function saveMerchantForEmail(
  email: string,
  merchant: Omit<Merchant, "id" | "ownerEmail" | "createdAt" | "updatedAt"> & Partial<Pick<Merchant, "id">>,
): Merchant {
  const ownerEmail = normalizeEmail(email);
  if (!ownerEmail) throw new Error("E-mail inválido.");

  const now = Date.now();
  const existing = getMerchantByEmail(ownerEmail);
  const id = existing?.id ?? merchant.id ?? createId("m");

  const next: Merchant = {
    id,
    ownerEmail,
    displayName: merchant.displayName.trim(),
    description: merchant.description.trim(),
    category: merchant.category.trim(),
    logoUrl: merchant.logoUrl?.trim() || undefined,
    byxAddress: normalizeByxAddress(merchant.byxAddress),
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
  };

  const all = loadMerchants();
  const filtered = all.filter((m) => m.id !== id && normalizeEmail(m.ownerEmail) !== ownerEmail);
  filtered.push(next);
  saveMerchants(filtered);
  return next;
}
