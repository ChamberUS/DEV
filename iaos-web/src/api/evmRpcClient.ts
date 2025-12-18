import { EVM_RPC_URL } from "@/config/chain";

type Ok<T> = { ok: true; data: T };
type Err = { ok: false; error: string; hint?: string; supported?: boolean };
export type RpcResult<T> = Ok<T> | Err;

function isEnabled(): boolean {
  return typeof EVM_RPC_URL === "string" && EVM_RPC_URL.length > 0;
}

export async function callRpc(method: string, params: unknown[] = []): Promise<RpcResult<any>> {
  if (!isEnabled()) return { ok: false, supported: false, error: "EVM RPC desabilitado (VITE_EVM_RPC_URL vazio)." };

  try {
    const response = await fetch(EVM_RPC_URL!, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
    });
    const json = (await response.json()) as any;
    if (json?.error) return { ok: false, error: json.error.message ?? "Erro RPC" };
    return { ok: true, data: json?.result };
  } catch (err) {
    const message = err instanceof Error ? err.message : "Falha ao conectar";
    return { ok: false, error: message, hint: `Verifique VITE_EVM_RPC_URL (${EVM_RPC_URL})` };
  }
}

export async function getChainId(): Promise<RpcResult<string>> {
  return callRpc("eth_chainId");
}

export async function getBlockNumber(): Promise<RpcResult<string>> {
  return callRpc("eth_blockNumber");
}

