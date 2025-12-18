const DEFAULT_DECIMALS = 6;

function normalizeAmountInput(value: string): string {
  return value.trim().replace(/,/g, "."); // allow pt-BR input
}

function splitDecimal(value: string): { intPart: string; fracPart: string } | null {
  const normalized = normalizeAmountInput(value);
  if (normalized.length === 0) return null;

  if (!/^\d+(\.\d+)?$/.test(normalized)) return null;
  const [intPart, fracPart = ""] = normalized.split(".");
  return { intPart, fracPart };
}

export function toMicro(amountByx: string | number, decimals = DEFAULT_DECIMALS): string {
  const raw = typeof amountByx === "number" ? String(amountByx) : String(amountByx);
  const parts = splitDecimal(raw);
  if (!parts) throw new Error("Valor inválido.");

  const intPart = parts.intPart.replace(/^0+(?=\d)/, "");
  const fracPadded = parts.fracPart.padEnd(decimals, "0").slice(0, decimals);
  const microStr = `${intPart || "0"}${fracPadded}`.replace(/^0+(?=\d)/, "");
  return BigInt(microStr || "0").toString();
}

export function fromMicro(micro: string | number | bigint, decimals = DEFAULT_DECIMALS): string {
  const raw = typeof micro === "bigint" ? micro.toString() : String(micro);
  const cleaned = raw.trim();
  if (!/^\d+$/.test(cleaned)) throw new Error("Valor inválido.");

  const value = cleaned.replace(/^0+(?=\d)/, "");
  const padded = value.padStart(decimals + 1, "0");
  const intPart = padded.slice(0, -decimals);
  const fracPart = padded.slice(-decimals).replace(/0+$/, "");
  return fracPart.length > 0 ? `${intPart}.${fracPart}` : intPart;
}

export function formatByx(amountByx: string | number, options?: { minimumFractionDigits?: number; maximumFractionDigits?: number }): string {
  const normalized = normalizeAmountInput(String(amountByx));
  const numeric = Number(normalized);
  if (!Number.isFinite(numeric)) return String(amountByx);
  return numeric.toLocaleString("pt-BR", {
    minimumFractionDigits: options?.minimumFractionDigits ?? 0,
    maximumFractionDigits: options?.maximumFractionDigits ?? DEFAULT_DECIMALS,
  });
}

export function formatMicro(micro: string | number | bigint, decimals = DEFAULT_DECIMALS): string {
  const byx = fromMicro(micro, decimals);
  return formatByx(byx, { maximumFractionDigits: decimals });
}

