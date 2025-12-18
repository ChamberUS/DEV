import React, { useEffect, useMemo, useState } from 'react';
import GlassCard from '@/components/ui/GlassCard';
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { toast } from 'sonner';
import { getTxsBySender } from '@/api/chainRestClient';
import { useAuth } from '@/auth/AuthContext';

const LAST_ADDRESS_KEY = 'aios_last_address';
const USER_PROFILE_KEY = 'aios_user_profile';

function loadLastAddress() {
  try {
    return window.localStorage.getItem(LAST_ADDRESS_KEY) || '';
  } catch {
    return '';
  }
}

function loadProfileAddress(email) {
  try {
    const raw = window.localStorage.getItem(USER_PROFILE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === 'object' && parsed.email === email && typeof parsed.byxAddress === 'string') {
      return parsed.byxAddress;
    }
    return null;
  } catch {
    return null;
  }
}

function formatTimestamp(ts) {
  try {
    return new Date(ts).toLocaleString('pt-BR');
  } catch {
    return ts || '-';
  }
}

export default function Transactions() {
  const { userSession } = useAuth();
  const userEmail = userSession?.email ?? null;

  const initialAddress = useMemo(() => {
    const last = loadLastAddress();
    if (userEmail) return loadProfileAddress(userEmail) || last;
    return last;
  }, [userEmail]);

  const [address, setAddress] = useState(initialAddress);
  const [loading, setLoading] = useState(false);
  const [supported, setSupported] = useState(true);
  const [txs, setTxs] = useState([]);
  const [selectedTx, setSelectedTx] = useState(null);

  async function fetchTxs() {
    const trimmed = address.trim();
    if (!trimmed) {
      toast.error('Informe um endereço.');
      return;
    }
    if (loading) return;

    setLoading(true);
    setSupported(true);
    try {
      const result = await getTxsBySender(trimmed);
      if (!result.ok) {
        if (result.supported === false) setSupported(false);
        toast.error(result.error || 'Não foi possível consultar transações.');
        setTxs([]);
        return;
      }

      const list = result.data?.tx_responses || [];
      setTxs(Array.isArray(list) ? list : []);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (address.trim()) fetchTxs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="p-6 lg:p-8">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Transações</h1>
          <p className="text-white/50">Consulta read-only via Cosmos REST (quando suportado).</p>
        </div>
      </div>

      <GlassCard className="p-6 space-y-4 mb-4">
        <div className="grid grid-cols-1 md:grid-cols-[1fr_auto] gap-3 items-end">
          <div className="space-y-2">
            <Label className="text-white/70">Endereço BYX</Label>
            <Input
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              placeholder="byx1..."
              className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
            />
          </div>
          <Button
            type="button"
            disabled={loading}
            onClick={fetchTxs}
            className="h-12 bg-white/5 text-white/80 hover:bg-white/10"
          >
            {loading ? 'Consultando...' : 'Consultar'}
          </Button>
        </div>

        {!supported && (
          <div className="text-sm text-white/50">
            Explorador em breve — sem endpoint de indexação disponível para buscar transações por endereço nesta rede.
          </div>
        )}
      </GlassCard>

      <GlassCard className="divide-y divide-white/5">
        {loading ? (
          <div className="p-6 text-white/50">Carregando...</div>
        ) : txs.length === 0 ? (
          <div className="p-6 text-white/50">Nenhuma transação encontrada (ou consulta não suportada).</div>
        ) : (
          txs.map((tx) => (
            <button
              key={tx.txhash}
              type="button"
              onClick={() => setSelectedTx(tx)}
              className="w-full text-left p-4 hover:bg-white/5 transition-colors"
            >
              <div className="flex items-center justify-between gap-4">
                <div className="min-w-0">
                  <div className="text-white font-medium truncate">{tx.txhash}</div>
                  <div className="text-xs text-white/50">
                    height {tx.height} • {formatTimestamp(tx.timestamp)}
                  </div>
                </div>
                <Badge
                  variant="outline"
                  className={tx.code === 0 ? 'text-emerald-400 border-emerald-400/30' : 'text-rose-400 border-rose-400/30'}
                >
                  {tx.code === 0 ? 'OK' : `Erro ${tx.code}`}
                </Badge>
              </div>
            </button>
          ))
        )}
      </GlassCard>

      <Dialog open={Boolean(selectedTx)} onOpenChange={(open) => !open && setSelectedTx(null)}>
        <DialogContent className="max-w-2xl bg-[#0a0a0a] border-[#1a4d2e]/50 text-white">
          <DialogHeader>
            <DialogTitle>Detalhes da transação</DialogTitle>
          </DialogHeader>
          {selectedTx && (
            <div className="space-y-3 text-sm">
              <div><span className="text-white/40">Hash:</span> <span className="break-all">{selectedTx.txhash}</span></div>
              <div><span className="text-white/40">Height:</span> {selectedTx.height}</div>
              <div><span className="text-white/40">Timestamp:</span> {formatTimestamp(selectedTx.timestamp)}</div>
              <div><span className="text-white/40">Code:</span> {selectedTx.code}</div>
              {selectedTx.raw_log && (
                <div>
                  <div className="text-white/40 mb-1">Raw log:</div>
                  <pre className="whitespace-pre-wrap text-xs bg-white/5 p-3 rounded-lg border border-white/10 max-h-64 overflow-auto">
                    {selectedTx.raw_log}
                  </pre>
                </div>
              )}
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

