import { getTxByHash } from "@/api/chainRestClient";

export type WaitForTxParams = {
  txhash: string;
  timeoutMs?: number;
  intervalMs?: number;
  memoNeedle?: string;
  onTick?: (elapsedMs: number) => void;
};

export type WaitForTxResult = {
  confirmed: boolean;
  tx?: any;
  failed?: boolean;
  rawLog?: string;
  code?: number;
  unsupported?: boolean;
};

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export async function waitForTx(params: WaitForTxParams): Promise<WaitForTxResult> {
  const timeoutMs = params.timeoutMs ?? 45000;
  const intervalMs = params.intervalMs ?? 2000;
  const startedAt = Date.now();

  while (Date.now() - startedAt <= timeoutMs) {
    params.onTick?.(Date.now() - startedAt);

    const txRes = await getTxByHash(params.txhash);

    if (txRes.ok) {
      const code = Number(txRes.data?.tx_response?.code ?? 0);
      if (code !== 0) {
        return {
          confirmed: false,
          failed: true,
          rawLog: typeof txRes.data?.tx_response?.raw_log === "string" ? txRes.data.tx_response.raw_log : undefined,
          code,
          tx: txRes.data,
        };
      }

      const memo = txRes.data?.tx?.body?.memo;
      if (params.memoNeedle && typeof memo === "string" && !memo.includes(params.memoNeedle)) {
        await sleep(intervalMs);
        continue;
      }

      return { confirmed: true, tx: txRes.data };
    } else {
      const errLike = txRes as { supported?: boolean };
      if (errLike.supported === false) {
        return { confirmed: false, unsupported: true };
      }
    }

    await sleep(intervalMs);
  }

  return { confirmed: false };
}
