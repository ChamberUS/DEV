import { broadcastTx, getAccount } from "@/api/chainRestClient";
import { CHAIN_ID, DENOM, RPC_URL_FOR_BROWSER } from "@/config/chain";
import { connectKeplr } from "@/wallet/keplr";

export type SendPaymentStep = "connecting" | "signing" | "broadcasting_rpc" | "fallback_to_rest" | "broadcasting_rest";

export type SendPaymentParams = {
  toAddress: string;
  amountMicro: string;
  denom: string;
  memo?: string;
  onStep?: (step: SendPaymentStep) => void;
};

export type SendPaymentResult = { txhash: string; mode: "rpc" | "rest" };

function isNetworkErrorMessage(message: string): boolean {
  const normalized = message.toLowerCase();
  return (
    normalized.includes("failed to fetch") ||
    normalized.includes("cors") ||
    normalized.includes("network") ||
    normalized.includes("fetch") ||
    normalized.includes("timeout")
  );
}

function emitStep(cb: ((step: SendPaymentStep) => void) | undefined, step: SendPaymentStep) {
  if (typeof cb === "function") cb(step);
}

export async function sendPaymentWithKeplr(params: SendPaymentParams): Promise<SendPaymentResult> {
  emitStep(params.onStep, "connecting");

  const { address, signer } = await connectKeplr();
  const memo = (params.memo ?? "").trim();

  const { SigningStargateClient, GasPrice, calculateFee } = await import("@cosmjs/stargate");
  const gasPrice = GasPrice.fromString(`0.025${params.denom || DENOM}`);

  try {
    emitStep(params.onStep, "signing");
    emitStep(params.onStep, "broadcasting_rpc");

    const client = await SigningStargateClient.connectWithSigner(RPC_URL_FOR_BROWSER, signer, { gasPrice });
    const res = await client.sendTokens(
      address,
      params.toAddress,
      [{ denom: params.denom, amount: params.amountMicro }],
      "auto",
      memo,
    );

    if (res.code && res.code !== 0) {
      throw new Error(res.rawLog || `Transação falhou (code ${res.code})`);
    }

    const txhash = res.transactionHash;
    if (!txhash) throw new Error("Broadcast via RPC não retornou txhash.");

    return { txhash, mode: "rpc" };
  } catch (err) {
    const rawMessage = err instanceof Error ? err.message : String(err);
    if (!isNetworkErrorMessage(rawMessage)) throw err;
  }

  emitStep(params.onStep, "fallback_to_rest");

  const accountRes = await getAccount(address);
  if (!accountRes.ok) {
    const errMessage = (accountRes as { error?: string }).error || "Não foi possível carregar conta (accountNumber/sequence) para assinar.";
    throw new Error(errMessage);
  }
  const accountData = accountRes.data;

  emitStep(params.onStep, "signing");

  const offline = await SigningStargateClient.offline(signer, { gasPrice });
  const fee = calculateFee(200000, gasPrice);

  const msgs = [
    {
      typeUrl: "/cosmos.bank.v1beta1.MsgSend",
      value: {
        fromAddress: address,
        toAddress: params.toAddress,
        amount: [{ denom: params.denom, amount: params.amountMicro }],
      },
    },
  ];

  const txRaw = await offline.sign(
    address,
    msgs,
    fee,
    memo,
    {
      chainId: CHAIN_ID,
      accountNumber: accountData.accountNumber,
      sequence: accountData.sequence,
    },
  );

  emitStep(params.onStep, "broadcasting_rest");

  const { TxRaw } = await import("cosmjs-types/cosmos/tx/v1beta1/tx");
  const { toBase64 } = await import("@cosmjs/encoding");
  const txBytes = TxRaw.encode(txRaw).finish();

  const broadcastRes = await broadcastTx(toBase64(txBytes), "BROADCAST_MODE_SYNC");
  if (!broadcastRes.ok) {
    const errMessage = (broadcastRes as { error?: string }).error || "Falha ao broadcast via REST.";
    throw new Error(errMessage);
  }

  const txhash = broadcastRes.data?.tx_response?.txhash || null;
  if (!txhash) throw new Error("Broadcast via REST não retornou txhash.");

  return { txhash, mode: "rest" };
}
