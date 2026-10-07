package byx.service.chain;

/** Estado apresentável da chain pública (somente leitura). Só LIVE significa saudável; todo o resto falha fechado. */
public enum ChainState {
    NOT_CONFIGURED, CONNECTING, OFFLINE, SYNCING, LIVE, STALE, NETWORK_MISMATCH, ERROR
}
