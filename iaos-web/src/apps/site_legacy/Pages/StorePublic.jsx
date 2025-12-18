import React, { useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';
import GlassCard from '@/components/ui/GlassCard';
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { getMerchantById } from '@/merchants/merchantStore';
import { listLocalPaymentRequestsByMerchant } from '@/api/paymentsClient';
import { DENOM, formatDenomAmount } from '@/config/chain';

export default function StorePublic() {
  const { storeId } = useParams();
  const merchant = useMemo(() => (storeId ? getMerchantById(storeId) : null), [storeId]);
  const requests = useMemo(() => (merchant ? listLocalPaymentRequestsByMerchant(merchant.id) : []), [merchant?.id]);

  if (!merchant) {
    return (
      <div className="p-6 lg:p-8">
        <GlassCard className="p-8">
          <h1 className="text-2xl font-bold text-white mb-2">Loja não encontrada</h1>
          <p className="text-white/60 mb-6">Este link pode estar incorreto.</p>
          <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
            <Link to="/">Voltar</Link>
          </Button>
        </GlassCard>
      </div>
    );
  }

  return (
    <div className="p-6 lg:p-8">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div className="flex items-center gap-4">
          {merchant.logoUrl ? (
            <img src={merchant.logoUrl} alt={merchant.displayName} className="w-14 h-14 rounded-xl object-cover border border-white/10" />
          ) : (
            <div className="w-14 h-14 rounded-xl bg-white/5 border border-white/10 flex items-center justify-center text-white/60">
              AIOS
            </div>
          )}
          <div>
            <h1 className="text-2xl md:text-3xl font-bold text-white">{merchant.displayName}</h1>
            <p className="text-white/50">{merchant.category}</p>
          </div>
        </div>
        <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
          <Link to="/merchant">Área do lojista</Link>
        </Button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <GlassCard className="p-6 space-y-3">
          <h2 className="text-lg font-semibold text-white">Sobre</h2>
          <p className="text-white/70 text-sm">{merchant.description || 'Sem descrição.'}</p>
          <div className="text-sm text-white/60 break-all pt-3 border-t border-white/10">
            <span className="text-white/40">Endereço BYX:</span> {merchant.byxAddress}
          </div>
        </GlassCard>

        <GlassCard className="p-6 space-y-3">
          <h2 className="text-lg font-semibold text-white">Pedidos recentes</h2>
          {requests.length === 0 ? (
            <div className="text-white/50 text-sm">Nenhum pedido público disponível neste dispositivo.</div>
          ) : (
            <div className="space-y-2">
              {requests.slice(0, 5).map((r) => {
                const expired = r.status === 'expired' || r.expiresAt <= Date.now();
                const amountLabel = r.denom === DENOM ? formatDenomAmount(r.amount, r.denom) : `${r.amount} ${r.denom}`;
                return (
                  <div key={r.id} className="flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <div className="text-white/80 text-sm truncate">{r.memo || r.id}</div>
                      <div className="text-xs text-white/50">{amountLabel}</div>
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge
                        variant="outline"
                        className={
                          r.status === 'paid'
                            ? 'text-emerald-400 border-emerald-400/30'
                            : expired
                              ? 'text-rose-400 border-rose-400/30'
                              : 'text-amber-400 border-amber-400/30'
                        }
                      >
                        {r.status === 'paid' ? 'Pago' : expired ? 'Expirado' : 'Pendente'}
                      </Badge>
                      <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
                        <Link to={`/pay/${r.id}`}>Abrir</Link>
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
          <div className="text-xs text-white/40">
            Observação: por enquanto, pedidos públicos são lidos do storage local (fallback). Em breve via chain/indexador.
          </div>
        </GlassCard>
      </div>
    </div>
  );
}

