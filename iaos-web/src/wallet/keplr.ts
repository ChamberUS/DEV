import { BECH32_PREFIX, CHAIN_ID, DENOM, DENOM_DECIMALS, REST_URL_FOR_BROWSER, RPC_URL_FOR_BROWSER } from "@/config/chain";

declare global {
  interface Window {
    keplr?: any;
    getOfflineSigner?: (chainId: string) => any;
    getOfflineSignerAuto?: (chainId: string) => any;
  }
}

export function isKeplrInstalled(): boolean {
  return typeof window !== "undefined" && Boolean(window.keplr);
}

function toAbsoluteUrl(url: string): string {
  if (typeof window === "undefined") return url;
  if (/^https?:\/\//i.test(url)) return url;
  return new URL(url, window.location.origin).toString();
}

export async function suggestChain(): Promise<void> {
  if (!isKeplrInstalled()) throw new Error("Keplr não está instalado.");

  const keplr = window.keplr!;
  if (typeof keplr.experimentalSuggestChain !== "function") return;

  const chainName = CHAIN_ID.toLowerCase().includes("test") ? "AIOS Testnet" : "AIOS";

  await keplr.experimentalSuggestChain({
    chainId: CHAIN_ID,
    chainName,
    rpc: toAbsoluteUrl(RPC_URL_FOR_BROWSER),
    rest: toAbsoluteUrl(REST_URL_FOR_BROWSER),
    bip44: { coinType: 118 },
    bech32Config: {
      bech32PrefixAccAddr: `${BECH32_PREFIX}`,
      bech32PrefixAccPub: `${BECH32_PREFIX}pub`,
      bech32PrefixValAddr: `${BECH32_PREFIX}valoper`,
      bech32PrefixValPub: `${BECH32_PREFIX}valoperpub`,
      bech32PrefixConsAddr: `${BECH32_PREFIX}valcons`,
      bech32PrefixConsPub: `${BECH32_PREFIX}valconspub`,
    },
    currencies: [
      {
        coinDenom: "BYX",
        coinMinimalDenom: DENOM,
        coinDecimals: DENOM_DECIMALS,
      },
    ],
    feeCurrencies: [
      {
        coinDenom: "BYX",
        coinMinimalDenom: DENOM,
        coinDecimals: DENOM_DECIMALS,
      },
    ],
    stakeCurrency: {
      coinDenom: "BYX",
      coinMinimalDenom: DENOM,
      coinDecimals: DENOM_DECIMALS,
    },
    gasPriceStep: {
      low: 0.01,
      average: 0.025,
      high: 0.04,
    },
    features: ["stargate", "ibc-transfer"],
  });
}

export async function connectKeplr(): Promise<{ address: string; signer: any }> {
  if (!isKeplrInstalled()) throw new Error("Keplr não está instalado.");

  try {
    await suggestChain();
  } catch {
    // ignore suggest errors; chain may already exist
  }

  await window.keplr!.enable(CHAIN_ID);

  const signer =
    (typeof window.getOfflineSignerAuto === "function" && (await window.getOfflineSignerAuto(CHAIN_ID))) ||
    (typeof window.getOfflineSigner === "function" && window.getOfflineSigner(CHAIN_ID));

  if (!signer) throw new Error("Não foi possível obter signer do Keplr.");

  const accounts = await signer.getAccounts();
  const address = accounts?.[0]?.address;
  if (!address) throw new Error("Nenhuma conta disponível no Keplr.");
  return { address, signer };
}

export async function getKey(): Promise<string> {
  if (!isKeplrInstalled()) throw new Error("Keplr não está instalado.");
  await window.keplr!.enable(CHAIN_ID);
  const key = await window.keplr!.getKey(CHAIN_ID);
  return key.bech32Address;
}
