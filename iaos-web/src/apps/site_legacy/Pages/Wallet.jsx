import React, { useEffect, useMemo, useState } from 'react';
import GlassCard from '@/components/ui/GlassCard';
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { toast } from 'sonner';
import { getBalances } from '@/api/chainRestClient';
import { DENOM, formatDenomAmount } from '@/config/chain';
import { useAuth } from '@/auth/AuthContext';
import { connectKeplr, isKeplrInstalled } from '@/wallet/keplr';

const LAST_ADDRESS_KEY = 'aios_last_address';
const USER_PROFILE_KEY = 'aios_user_profile';

function loadLastAddress() {
  try {
    return window.localStorage.getItem(LAST_ADDRESS_KEY) || '';
  } catch {
    return '';
  }
}

function saveLastAddress(value) {
  try {
    window.localStorage.setItem(LAST_ADDRESS_KEY, value);
  } catch {
    // ignore
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

function saveProfileAddress(email, address) {
  try {
    window.localStorage.setItem(USER_PROFILE_KEY, JSON.stringify({ email, byxAddress: address }));
  } catch {
    // ignore
  }
}

export default function Wallet() {
  const { userSession } = useAuth();
  const userEmail = userSession?.email ?? null;

  const initialAddress = useMemo(() => {
    const last = loadLastAddress();
    if (userEmail) return loadProfileAddress(userEmail) || last;
    return last;
  }, [userEmail]);

  const [address, setAddress] = useState(initialAddress);
  const [savingToProfile, setSavingToProfile] = useState(Boolean(userEmail));
  const [loading, setLoading] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [balances, setBalances] = useState([]);

  useEffect(() => {
    setSavingToProfile(Boolean(userEmail));
  }, [userEmail]);

  async function onQuery() {
    const trimmed = address.trim();
    if (!trimmed) {
      toast.error('Informe um endereço.');
      return;
    }
    if (loading) return;

    setLoading(true);
    try {
      const result = await getBalances(trimmed);
      if (!result.ok) {
        toast.error(result.error || 'Não foi possível consultar.');
        return;
      }
      const list = result.data?.balances || [];
      setBalances(Array.isArray(list) ? list : []);
      saveLastAddress(trimmed);
      if (userEmail && savingToProfile) saveProfileAddress(userEmail, trimmed);
    } finally {
      setLoading(false);
    }
  }

  async function onConnectKeplr() {
    if (connecting) return;
    if (!isKeplrInstalled()) {
      toast.error('Keplr não está instalado. Instale a extensão e tente novamente.');
      return;
    }

    setConnecting(true);
    try {
      const { address: addr } = await connectKeplr();
      setAddress(addr);
      saveLastAddress(addr);
      if (userEmail && savingToProfile) saveProfileAddress(userEmail, addr);
      toast.success('Carteira conectada!');
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Não foi possível conectar ao Keplr.';
      toast.error(message);
    } finally {
      setConnecting(false);
    }
  }

  const denomBalance = balances.find((c) => c.denom === DENOM)?.amount ?? '0';

  return (
    <div className="p-6 lg:p-8">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Carteira</h1>
          <p className="text-white/50">Consulta read-only de saldos na chain.</p>
        </div>
        <Button
          type="button"
          disabled={connecting}
          onClick={onConnectKeplr}
          className="bg-white/5 text-white/80 hover:bg-white/10"
        >
          {connecting ? 'Conectando...' : 'Conectar Keplr'}
        </Button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <GlassCard className="p-6 space-y-4">
          <div className="space-y-2">
            <Label className="text-white/70">Endereço BYX</Label>
            <Input
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              placeholder="byx1..."
              className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
            />
          </div>

          {userEmail && (
            <label className="flex items-center gap-2 text-sm text-white/60">
              <input
                type="checkbox"
                checked={savingToProfile}
                onChange={(e) => setSavingToProfile(e.target.checked)}
              />
              Associar este endereço ao meu perfil ({userEmail})
            </label>
          )}

          <Button
            type="button"
            disabled={loading}
            onClick={onQuery}
            className="w-full h-12 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-semibold"
          >
            {loading ? 'Consultando...' : 'Consultar'}
          </Button>
        </GlassCard>

        <GlassCard className="p-6 space-y-3">
          <h2 className="text-lg font-semibold text-white">Saldo</h2>
          <div className="text-2xl font-bold text-emerald-400">{formatDenomAmount(denomBalance, DENOM)}</div>
          <div className="text-sm text-white/50">Denom: <code>{DENOM}</code></div>

          <div className="pt-3 border-t border-white/10">
            <h3 className="text-white/70 font-medium mb-2">Outros saldos</h3>
            {balances.length === 0 ? (
              <div className="text-white/40 text-sm">Sem dados ainda (consulte um endereço).</div>
            ) : (
              <div className="space-y-2">
                {balances.map((c) => (
                  <div key={`${c.denom}:${c.amount}`} className="flex items-center justify-between text-sm">
                    <span className="text-white/60">{c.denom}</span>
                    <span className="text-white">{c.denom === DENOM ? formatDenomAmount(c.amount, c.denom) : `${c.amount} ${c.denom}`}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </GlassCard>
      </div>
    </div>
  );
}
