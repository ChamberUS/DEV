import { REST_URL_FOR_BROWSER } from "@/config/chain";

type Ok<T> = { ok: true; data: T };
type Err = { ok: false; error: string; hint?: string; status?: number; supported?: boolean };
export type Result<T> = Ok<T> | Err;

function normalizeUrl(url: string): string {
  return url.replace(/\/+$/, "");
}

function userFacingNetworkHint(baseUrl: string): string {
  return `Verifique se o REST está acessível (ou habilite o proxy do Vite). URL atual: ${baseUrl}`;
}

async function requestJson<T>(path: string): Promise<Result<T>> {
  const baseUrl = normalizeUrl(REST_URL_FOR_BROWSER);
  const url = `${baseUrl}${path.startsWith("/") ? "" : "/"}${path}`;

  try {
    const response = await fetch(url, { headers: { Accept: "application/json" } });
    const text = await response.text();
    const body = text ? (JSON.parse(text) as unknown) : null;

    if (!response.ok) {
      const message =
        typeof body === "object" && body && "message" in (body as any) && typeof (body as any).message === "string"
          ? (body as any).message
          : `HTTP ${response.status}`;
      return {
        ok: false,
        error: message,
        status: response.status,
        hint: userFacingNetworkHint(baseUrl),
      };
    }

    return { ok: true, data: body as T };
  } catch (err) {
    const message = err instanceof Error ? err.message : "Falha ao conectar";
    return { ok: false, error: message, hint: userFacingNetworkHint(REST_URL_FOR_BROWSER) };
  }
}

async function requestJsonPost<T>(path: string, body: unknown): Promise<Result<T>> {
  const baseUrl = normalizeUrl(REST_URL_FOR_BROWSER);
  const url = `${baseUrl}${path.startsWith("/") ? "" : "/"}${path}`;

  try {
    const response = await fetch(url, {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const text = await response.text();
    const parsed = text ? (JSON.parse(text) as unknown) : null;

    if (!response.ok) {
      const message =
        typeof parsed === "object" && parsed && "message" in (parsed as any) && typeof (parsed as any).message === "string"
          ? (parsed as any).message
          : `HTTP ${response.status}`;
      return {
        ok: false,
        error: message,
        status: response.status,
        hint: userFacingNetworkHint(baseUrl),
      };
    }

    return { ok: true, data: parsed as T };
  } catch (err) {
    const message = err instanceof Error ? err.message : "Falha ao conectar";
    return { ok: false, error: message, hint: userFacingNetworkHint(REST_URL_FOR_BROWSER) };
  }
}

async function requestJsonWithFallback<T>(paths: string[]): Promise<Result<T>> {
  let lastError: Err | null = null;
  for (const path of paths) {
    const result = await requestJson<T>(path);
    if (result.ok === true) return result;
    const err = result as Err;
    lastError = err;
    if (typeof err.status === "number" && err.status !== 404) break;
  }
  return lastError ?? { ok: false, error: "Falha ao consultar endpoint" };
}

export async function getNodeInfo(): Promise<Result<any>> {
  return requestJsonWithFallback<any>([
    "/cosmos/base/tendermint/v1beta1/node_info",
    "/node_info",
  ]);
}

export async function getLatestBlock(): Promise<Result<any>> {
  return requestJsonWithFallback<any>([
    "/cosmos/base/tendermint/v1beta1/blocks/latest",
    "/blocks/latest",
  ]);
}

export async function getSupply(denom: string): Promise<Result<any>> {
  return requestJson<any>(`/cosmos/bank/v1beta1/supply/${encodeURIComponent(denom)}`);
}

export async function getBalances(address: string): Promise<Result<any>> {
  const trimmed = address.trim();
  if (!trimmed) return { ok: false, error: "Endereço vazio." };
  return requestJson<any>(`/cosmos/bank/v1beta1/balances/${encodeURIComponent(trimmed)}`);
}

export async function getTxsByEvents(events: string[], limit = 50): Promise<Result<any>> {
  const cleaned = events.map((e) => e.trim()).filter(Boolean);
  if (cleaned.length === 0) return { ok: false, error: "Nenhum filtro de evento informado." };

  const params = new URLSearchParams();
  for (const e of cleaned) params.append("events", e);
  params.set("order_by", "ORDER_BY_DESC");
  params.set("pagination.limit", String(Math.max(1, Math.min(200, Number(limit) || 50))));

  const result = await requestJson<any>(`/cosmos/tx/v1beta1/txs?${params.toString()}`);
  if (result.ok) return result;

  const err = result as Err;
  const status = err.status;
  if (status === 404 || status === 501) {
    return {
      ok: false,
      supported: false,
      error: "Endpoint de transações não disponível no REST desta rede.",
      hint: "Algumas redes precisam de indexador/serviço extra para consultar transações por eventos/endereço.",
      status,
    };
  }

  return err;
}

export async function getTxsBySender(address: string): Promise<Result<any>> {
  const trimmed = address.trim();
  if (!trimmed) return { ok: false, error: "Endereço vazio." };

  return getTxsByEvents([`message.sender='${trimmed}'`], 50);
}

export async function getTxByHash(hash: string): Promise<Result<any>> {
  const trimmed = hash.trim();
  if (!trimmed) return { ok: false, error: "Hash vazio." };

  const result = await requestJson<any>(`/cosmos/tx/v1beta1/txs/${encodeURIComponent(trimmed)}`);
  if (result.ok) return result;

  const err = result as Err;
  const status = err.status;
  if (status === 404) {
    return {
      ok: false,
      supported: true,
      error: "Tx ainda não encontrada (aguardando inclusão/indexação).",
      status,
    };
  }
  if (status === 501) {
    return {
      ok: false,
      supported: false,
      error: "Endpoint de consulta por hash não disponível no REST desta rede.",
      status,
    };
  }

  return err;
}

function extractBaseAccount(account: any): any | null {
  if (!account || typeof account !== "object") return null;
  if (account.base_account) return account.base_account;
  if (account.baseAccount) return account.baseAccount;

  const baseVesting = account.base_vesting_account ?? account.baseVestingAccount;
  if (baseVesting?.base_account) return baseVesting.base_account;
  if (baseVesting?.baseAccount) return baseVesting.baseAccount;

  return null;
}

export async function getAccount(address: string): Promise<Result<{ accountNumber: number; sequence: number }>> {
  const trimmed = address.trim();
  if (!trimmed) return { ok: false, error: "Endereço vazio." };

  const result = await requestJson<any>(`/cosmos/auth/v1beta1/accounts/${encodeURIComponent(trimmed)}`);
  if (!result.ok) return result as Err;

  const account = (result.data as any)?.account;
  const base = extractBaseAccount(account) ?? account;
  const accountNumberRaw = base?.account_number ?? base?.accountNumber;
  const sequenceRaw = base?.sequence;

  const accountNumber = Number(accountNumberRaw);
  const sequence = Number(sequenceRaw);
  if (!Number.isFinite(accountNumber) || !Number.isFinite(sequence)) {
    return { ok: false, error: "Não foi possível ler accountNumber/sequence da conta no REST." };
  }

  return { ok: true, data: { accountNumber, sequence } };
}

export async function broadcastTx(
  txBytesBase64: string,
  mode: "BROADCAST_MODE_SYNC" | "BROADCAST_MODE_BLOCK" | "BROADCAST_MODE_ASYNC" = "BROADCAST_MODE_SYNC",
): Promise<Result<any>> {
  const trimmed = txBytesBase64.trim();
  if (!trimmed) return { ok: false, error: "tx_bytes vazio." };
  return requestJsonPost<any>("/cosmos/tx/v1beta1/txs", { tx_bytes: trimmed, mode });
}
