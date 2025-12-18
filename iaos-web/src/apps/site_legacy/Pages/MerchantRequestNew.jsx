import React, { useMemo, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import GlassCard from '@/components/ui/GlassCard';
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { toast } from 'sonner';
import { useAuth } from '@/auth/AuthContext';
import { getMerchantByEmail, isPlausibleByxAddress } from '@/merchants/merchantStore';
import { createLocalPaymentRequest } from '@/api/paymentsClient';
import { DENOM } from '@/config/chain';
import { formatByx, formatMicro, toMicro } from '@/utils/amount';

export default function MerchantRequestNew() {
  const { userSession } = useAuth();
  const email = userSession?.email ?? null;
  const navigate = useNavigate();
  if (!email) return <Navigate to="/auth/login" replace />;

  const merchant = useMemo(() => getMerchantByEmail(email), [email]);
  if (!merchant) return <Navigate to="/merchant/setup" replace />;
  const merchantAddressOk = isPlausibleByxAddress(merchant.byxAddress);

  const [amountDisplay, setAmountDisplay] = useState('10');
  const [denomMode, setDenomMode] = useState('BYX'); // BYX | UBYX
  const [memo, setMemo] = useState('');
  const [expiresInSeconds, setExpiresInSeconds] = useState('3600');
  const [creating, setCreating] = useState(false);

  const computedBase = useMemo(() => {
    const raw = amountDisplay.trim();
    if (!raw) return null;
    try {
      if (denomMode === 'UBYX') {
        if (!/^\d+$/.test(raw)) return null;
        return raw.replace(/^0+(?=\d)/, '');
      }
      const micro = toMicro(raw, 6);
      if (BigInt(micro) <= 0n) return null;
      return micro;
    } catch {
      return null;
    }
  }, [amountDisplay, denomMode]);

  const preview = computedBase ? `${formatMicro(computedBase, 6)} ${DENOM.startsWith('u') ? DENOM.slice(1).toUpperCase() : DENOM.toUpperCase()}` : '-';

  async function onSubmit(e) {
    e.preventDefault();
    if (creating) return;
    if (!merchantAddressOk) {
      toast.error('Adicione um endereço BYX válido na sua loja para criar cobranças.');
      return;
    }
    if (!computedBase) {
      toast.error('Informe um valor válido.');
      return;
    }

    setCreating(true);
    try {
      const req = createLocalPaymentRequest({
        merchantId: merchant.id,
        ownerEmail: email,
        amount: computedBase,
        denom: DENOM,
        description: memo,
        expiresInSeconds: Number(expiresInSeconds) || 3600,
      });
      toast.success('Pedido criado!');
      navigate(`/pay/${req.id}`, { replace: true });
    } finally {
      setCreating(false);
    }
  }

  return (
    <div className="p-6 lg:p-8">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Novo pedido</h1>
          <p className="text-white/50">{merchant.displayName}</p>
        </div>
        <div className="flex gap-3">
          <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
            <Link to="/merchant/requests">Voltar</Link>
          </Button>
        </div>
      </div>

      <GlassCard className="p-8">
        {!merchantAddressOk && (
          <div className="mb-4 p-4 rounded-lg border border-rose-400/30 bg-rose-500/10 text-rose-200 text-sm">
            Sua loja não tem um endereço BYX válido.{' '}
            <Link className="underline" to="/merchant/setup">Adicionar endereço da loja</Link>
          </div>
        )}
        <form onSubmit={onSubmit} className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label className="text-white/70">Valor</Label>
              <Input
                value={amountDisplay}
                onChange={(e) => setAmountDisplay(e.target.value)}
                placeholder={denomMode === 'BYX' ? '10.5' : '10000000'}
                className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
              />
              <div className="text-xs text-white/50">
                Prévia: {denomMode === 'BYX' ? `${formatByx(amountDisplay)} BYX` : `${amountDisplay || '-'} ubyx`} → {preview}
              </div>
            </div>
            <div className="space-y-2">
              <Label className="text-white/70">Unidade</Label>
              <div className="flex gap-2">
                <Button
                  type="button"
                  onClick={() => setDenomMode('BYX')}
                  className={denomMode === 'BYX' ? 'bg-white/10 text-white' : 'bg-white/5 text-white/70 hover:bg-white/10'}
                >
                  BYX
                </Button>
                <Button
                  type="button"
                  onClick={() => setDenomMode('UBYX')}
                  className={denomMode === 'UBYX' ? 'bg-white/10 text-white' : 'bg-white/5 text-white/70 hover:bg-white/10'}
                >
                  ubyx
                </Button>
              </div>
              <div className="text-xs text-white/50">O pedido será salvo em <code>{DENOM}</code> (base units).</div>
            </div>
          </div>

          <div className="space-y-2">
            <Label className="text-white/70">Descrição (opcional)</Label>
            <Input
              value={memo}
              onChange={(e) => setMemo(e.target.value)}
              placeholder="Ex: Pedido #123"
              className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
            />
            <div className="text-xs text-white/50">Será incluído no memo junto com o ID do pedido.</div>
          </div>

          <div className="space-y-2">
            <Label className="text-white/70">Expira em (segundos)</Label>
            <Input
              value={expiresInSeconds}
              onChange={(e) => setExpiresInSeconds(e.target.value)}
              placeholder="3600"
              className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
            />
          </div>

          <Button
            type="submit"
            disabled={creating || !merchantAddressOk}
            className="w-full h-12 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-semibold"
          >
            {creating ? 'Criando...' : 'Criar pedido'}
          </Button>
        </form>
      </GlassCard>
    </div>
  );
}
