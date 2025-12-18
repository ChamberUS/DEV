function readEnvString(key: string, fallback: string): string {
  const value = import.meta.env[key];
  if (typeof value !== "string") return fallback;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : fallback;
}

function readEnvOptionalString(key: string): string | null {
  const value = import.meta.env[key];
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function readEnvBool(key: string, fallback: boolean): boolean {
  const value = import.meta.env[key];
  if (typeof value !== "string") return fallback;
  const normalized = value.trim().toLowerCase();
  if (normalized === "true" || normalized === "1" || normalized === "yes") return true;
  if (normalized === "false" || normalized === "0" || normalized === "no") return false;
  return fallback;
}

function readEnvInt(key: string, fallback: number): number {
  const value = import.meta.env[key];
  if (typeof value !== "string") return fallback;
  const parsed = Number.parseInt(value.trim(), 10);
  if (!Number.isFinite(parsed)) return fallback;
  return parsed;
}

export const CHAIN_REST_URL = readEnvString("VITE_CHAIN_REST_URL", "http://127.0.0.1:1317").replace(/\/+$/, "");
export const CHAIN_RPC_URL = readEnvString("VITE_CHAIN_RPC_URL", "http://127.0.0.1:26657").replace(/\/+$/, "");
export const EVM_RPC_URL = readEnvOptionalString("VITE_EVM_RPC_URL")?.replace(/\/+$/, "") ?? null;

export const CHAIN_ID = readEnvString("VITE_CHAIN_ID", "byx_1");
export const DENOM = readEnvString("VITE_DENOM", "ubyx");
export const BECH32_PREFIX = readEnvString("VITE_BECH32_PREFIX", "byx");

export const USE_CHAIN_PROXY = readEnvBool("VITE_USE_PROXY", true);
export const REST_URL_FOR_BROWSER = USE_CHAIN_PROXY ? "/rest" : CHAIN_REST_URL;
export const RPC_URL_FOR_BROWSER = USE_CHAIN_PROXY ? "/rpc" : CHAIN_RPC_URL;

export const DENOM_DECIMALS = Math.max(0, Math.min(18, readEnvInt("VITE_COIN_DECIMALS", 6)));

export function formatDenomAmount(amount: string, denom: string): string {
  const numeric = Number(amount);
  if (!Number.isFinite(numeric)) return `${amount} ${denom}`;
  if (denom !== DENOM) return `${numeric.toLocaleString("pt-BR")} ${denom}`;

  const value = numeric / 10 ** DENOM_DECIMALS;
  const displayDenom = DENOM.startsWith("u") ? DENOM.slice(1).toUpperCase() : DENOM.toUpperCase();
  return `${value.toLocaleString("pt-BR", { maximumFractionDigits: DENOM_DECIMALS })} ${displayDenom}`;
}
