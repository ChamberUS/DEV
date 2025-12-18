#!/usr/bin/env bash
set -euo pipefail

BYX_HOME="${BYX_HOME:-$HOME/.byx-testnet}"
DENOM="${DENOM:-ubyx}"

PUBLIC_SCHEME="${PUBLIC_SCHEME:-http}"
PUBLIC_HOST="${PUBLIC_HOST:-127.0.0.1}"
PUBLIC_PORT="${PUBLIC_PORT:-8080}"
PUBLIC_BASE="${PUBLIC_BASE:-$PUBLIC_SCHEME://$PUBLIC_HOST:$PUBLIC_PORT}"

RPC_INTERNAL="${RPC_INTERNAL:-http://127.0.0.1:26657}"
REST_INTERNAL="${REST_INTERNAL:-http://127.0.0.1:1317}"
GRPC_INTERNAL="${GRPC_INTERNAL:-127.0.0.1:9090}"
FAUCET_INTERNAL="${FAUCET_INTERNAL:-http://127.0.0.1:8000}"

CHAIN_ID="${CHAIN_ID:-}"
if [[ -z "${CHAIN_ID}" ]] && [[ -f "$BYX_HOME/config/genesis.json" ]]; then
  if command -v jq >/dev/null 2>&1; then
    CHAIN_ID="$(jq -r '.chain_id // empty' "$BYX_HOME/config/genesis.json" 2>/dev/null || true)"
  else
    CHAIN_ID="$(grep -Eo '\"chain_id\"\\s*:\\s*\"[^\"]+\"' "$BYX_HOME/config/genesis.json" | head -n1 | sed -E 's/.*\"chain_id\"\\s*:\\s*\"([^\"]+)\".*/\\1/' || true)"
  fi
fi
CHAIN_ID="${CHAIN_ID:-byx_1}"

echo "== AIOS/BYX Testnet – Endpoints =="
echo
echo "Chain:"
echo "  CHAIN_ID: $CHAIN_ID"
echo "  DENOM   : $DENOM"
echo
echo "Interno (host):"
echo "  RPC  : $RPC_INTERNAL"
echo "  REST : $REST_INTERNAL"
echo "  gRPC : $GRPC_INTERNAL"
echo "  Faucet: $FAUCET_INTERNAL"
echo
echo "Público (via reverse proxy):"
echo "  Base : $PUBLIC_BASE"
echo "  RPC  : $PUBLIC_BASE/rpc"
echo "  REST : $PUBLIC_BASE/rest"
echo "  Faucet: $PUBLIC_BASE/faucet"
echo
echo "Exemplos:"
echo "  curl -sS $PUBLIC_BASE/rpc/status | head"
echo "  curl -sS $PUBLIC_BASE/rest/cosmos/base/tendermint/v1beta1/node_info | head"
echo
echo "Keplr ChainInfo (resumo):"
echo "  chainId: $CHAIN_ID"
echo "  rpc    : $PUBLIC_BASE/rpc"
echo "  rest   : $PUBLIC_BASE/rest"
echo

