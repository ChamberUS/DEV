#!/usr/bin/env bash
set -euo pipefail

# Inicia um nó byxd usando um HOME dedicado, sem reset e com log em arquivo.
# Este script NÃO gera genesis nem altera consenso. Ele assume que o HOME já está inicializado.

BYXD_BIN="${BYXD_BIN:-byxd}"
BYX_HOME="${BYX_HOME:-$HOME/.byx-testnet}"
LOG_DIR="${LOG_DIR:-$BYX_HOME/logs}"
LOG_FILE="${LOG_FILE:-$LOG_DIR/byxd.log}"
PID_FILE="${PID_FILE:-$BYX_HOME/byxd.pid}"

# Flags extras: ex. START_FLAGS="--minimum-gas-prices 0.025ubyx"
START_FLAGS="${START_FLAGS:-}"

if ! command -v "$BYXD_BIN" >/dev/null 2>&1; then
  echo "Erro: binary '$BYXD_BIN' não encontrado no PATH."
  echo "Dica: export BYXD_BIN=/caminho/para/byxd"
  exit 1
fi

mkdir -p "$LOG_DIR"

if [[ -f "$PID_FILE" ]]; then
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [[ -n "${pid:-}" ]] && kill -0 "$pid" >/dev/null 2>&1; then
    echo "byxd já parece estar rodando (pid=$pid)."
    echo "Log: $LOG_FILE"
    exit 0
  fi
fi

if [[ ! -f "$BYX_HOME/config/genesis.json" ]]; then
  echo "Erro: HOME não inicializado: $BYX_HOME"
  echo "Arquivo ausente: $BYX_HOME/config/genesis.json"
  echo
  echo "Este script não cria genesis automaticamente."
  echo "Inicialize manualmente (exemplo):"
  echo "  $BYXD_BIN init aios-testnet --chain-id byx_1 --home \"$BYX_HOME\""
  echo "Depois configure genesis/validators/peers e rode novamente."
  exit 1
fi

echo "Iniciando byxd..."
echo "  BIN : $BYXD_BIN"
echo "  HOME: $BYX_HOME"
echo "  LOG : $LOG_FILE"
echo "  PID : $PID_FILE"

set +e
nohup "$BYXD_BIN" start --home "$BYX_HOME" $START_FLAGS >>"$LOG_FILE" 2>&1 &
pid="$!"
set -e

echo "$pid" >"$PID_FILE"
echo "OK: byxd iniciado (pid=$pid)"
echo "Tail log: tail -f \"$LOG_FILE\""

