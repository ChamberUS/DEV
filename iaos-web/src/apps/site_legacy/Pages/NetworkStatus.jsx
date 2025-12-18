import React, { useEffect, useState } from 'react';
import GlassCard from '@/components/ui/GlassCard';
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { CHAIN_ID, CHAIN_REST_URL, CHAIN_RPC_URL, DENOM, EVM_RPC_URL, REST_URL_FOR_BROWSER, RPC_URL_FOR_BROWSER, USE_CHAIN_PROXY } from '@/config/chain';
import { getLatestBlock, getNodeInfo, getSupply } from '@/api/chainRestClient';
import { getBlockNumber, getChainId } from '@/api/evmRpcClient';

function StatusBadge({ ok, labelOk = 'Conectado', labelErr = 'Não conectado' }) {
  return (
    <Badge
      variant="outline"
      className={ok ? 'text-emerald-400 border-emerald-400/30' : 'text-rose-400 border-rose-400/30'}
    >
      {ok ? labelOk : labelErr}
    </Badge>
  );
}

export default function NetworkStatus() {
  const [loading, setLoading] = useState(false);
  const [nodeInfo, setNodeInfo] = useState(null);
  const [latestBlock, setLatestBlock] = useState(null);
  const [supply, setSupplyState] = useState(null);
  const [evmInfo, setEvmInfo] = useState({ enabled: Boolean(EVM_RPC_URL), chainId: null, blockNumber: null });
  const [errors, setErrors] = useState([]);

  async function refresh() {
    if (loading) return;
    setLoading(true);
    setErrors([]);

    try {
      const [nodeRes, blockRes, supplyRes] = await Promise.all([
        getNodeInfo(),
        getLatestBlock(),
        getSupply(DENOM),
      ]);

      if (nodeRes.ok) setNodeInfo(nodeRes.data);
      else setErrors((prev) => [...prev, `Node info: ${nodeRes.error}`]);

      if (blockRes.ok) setLatestBlock(blockRes.data);
      else setErrors((prev) => [...prev, `Latest block: ${blockRes.error}`]);

      if (supplyRes.ok) setSupplyState(supplyRes.data);
      else setErrors((prev) => [...prev, `Supply: ${supplyRes.error}`]);

      if (EVM_RPC_URL) {
        const [chainIdRes, blockNumRes] = await Promise.all([getChainId(), getBlockNumber()]);
        setEvmInfo({
          enabled: true,
          chainId: chainIdRes.ok ? chainIdRes.data : null,
          blockNumber: blockNumRes.ok ? blockNumRes.data : null,
        });
        if (!chainIdRes.ok) setErrors((prev) => [...prev, `EVM chainId: ${chainIdRes.error}`]);
        if (!blockNumRes.ok) setErrors((prev) => [...prev, `EVM blockNumber: ${blockNumRes.error}`]);
      } else {
        setEvmInfo({ enabled: false, chainId: null, blockNumber: null });
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const nodeOk = Boolean(nodeInfo);
  const blockOk = Boolean(latestBlock);
  const supplyOk = Boolean(supply);

  const latestHeight =
    latestBlock?.block?.header?.height ??
    latestBlock?.block_id?.hash ??
    null;

  const supplyAmount = supply?.amount?.amount ?? supply?.supply?.amount ?? null;

  return (
    <div className="p-6 lg:p-8">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Status da Rede</h1>
          <p className="text-white/50">Debug read-only da conexão com a chain.</p>
        </div>
        <Button
          type="button"
          disabled={loading}
          className="bg-white/5 text-white/80 hover:bg-white/10"
          onClick={refresh}
        >
          {loading ? 'Atualizando...' : 'Atualizar'}
        </Button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <GlassCard className="p-6 space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold text-white">Config</h2>
          </div>
          <div className="text-sm text-white/60 space-y-1">
            <div><span className="text-white/40">CHAIN_ID:</span> {CHAIN_ID}</div>
            <div><span className="text-white/40">DENOM:</span> {DENOM}</div>
            <div><span className="text-white/40">Proxy Vite:</span> {USE_CHAIN_PROXY ? 'on' : 'off'}</div>
            <div className="break-all"><span className="text-white/40">REST (browser):</span> {REST_URL_FOR_BROWSER}</div>
            <div className="break-all"><span className="text-white/40">RPC (browser):</span> {RPC_URL_FOR_BROWSER}</div>
            <div className="break-all"><span className="text-white/40">REST (raw):</span> {CHAIN_REST_URL}</div>
            <div className="break-all"><span className="text-white/40">RPC (raw):</span> {CHAIN_RPC_URL}</div>
            <div className="break-all"><span className="text-white/40">EVM RPC:</span> {EVM_RPC_URL || '(desabilitado)'}</div>
          </div>
        </GlassCard>

        <GlassCard className="p-6 space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold text-white">Cosmos REST</h2>
            <StatusBadge ok={nodeOk && blockOk} />
          </div>
          <div className="text-sm text-white/60 space-y-2">
            <div className="flex items-center justify-between">
              <span>Node info</span>
              <StatusBadge ok={nodeOk} />
            </div>
            <div className="flex items-center justify-between">
              <span>Latest block</span>
              <StatusBadge ok={blockOk} />
            </div>
            <div className="flex items-center justify-between">
              <span>Supply ({DENOM})</span>
              <StatusBadge ok={supplyOk} />
            </div>
          </div>
        </GlassCard>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mt-4">
        <GlassCard className="p-6 space-y-3">
          <h2 className="text-lg font-semibold text-white">Detalhes</h2>
          <div className="text-sm text-white/60 space-y-1">
            <div><span className="text-white/40">Latest height:</span> {latestHeight || '-'}</div>
            <div><span className="text-white/40">Supply amount:</span> {supplyAmount || '-'}</div>
            <div><span className="text-white/40">Moniker:</span> {nodeInfo?.default_node_info?.moniker || '-'}</div>
            <div><span className="text-white/40">Network:</span> {nodeInfo?.default_node_info?.network || '-'}</div>
          </div>
        </GlassCard>

        <GlassCard className="p-6 space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold text-white">EVM (opcional)</h2>
            <StatusBadge ok={evmInfo.enabled && Boolean(evmInfo.chainId) && Boolean(evmInfo.blockNumber)} labelOk="Habilitado" />
          </div>
          <div className="text-sm text-white/60 space-y-1">
            <div><span className="text-white/40">chainId:</span> {evmInfo.chainId || '-'}</div>
            <div><span className="text-white/40">blockNumber:</span> {evmInfo.blockNumber || '-'}</div>
            {!evmInfo.enabled && (
              <div className="text-white/40">Defina <code>VITE_EVM_RPC_URL</code> para habilitar.</div>
            )}
          </div>
        </GlassCard>
      </div>

      {errors.length > 0 && (
        <GlassCard className="p-6 mt-4">
          <h3 className="text-white font-semibold mb-2">Dicas / Erros</h3>
          <ul className="list-disc pl-5 text-sm text-white/60 space-y-1">
            {errors.map((e, idx) => (
              <li key={idx}>{e}</li>
            ))}
          </ul>
          <div className="text-sm text-white/50 mt-3">
            Se estiver rodando localmente, confira se o node expõe REST em <code>1317</code> e permite CORS para o browser.
          </div>
        </GlassCard>
      )}
    </div>
  );
}
