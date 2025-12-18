import React, { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import GlassCard from '@/components/ui/GlassCard';
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { toast } from 'sonner';
import { useAuth } from '@/auth/AuthContext';
import { getMerchantByEmail } from '@/merchants/merchantStore';
import { listPaymentRequestsByMerchant } from '@/api/paymentsClient';
import { DENOM, formatDenomAmount } from '@/config/chain';

function formatDate(ms) {
  try {
    return new Date(ms).toLocaleString('pt-BR');
  } catch {
    return '-';
  }
}

export default function MerchantRequests() {
  const { userSession } = useAuth();
  const email = userSession?.email ?? null;
  const normalizedEmail = typeof email === 'string' ? email.trim().toLowerCase() : null;
  const navigate = useNavigate();
  if (!normalizedEmail) return <Navigate to="/auth/login" replace />;

  const merchant = useMemo(() => getMerchantByEmail(normalizedEmail), [normalizedEmail]);
  const [loading, setLoading] = useState(false);
  const [requests, setRequests] = useState([]);

  async function refresh() {
    if (!merchant) return;
    if (loading) return;
    setLoading(true);
    try {
      const data = await listPaymentRequestsByMerchant(merchant.id);
      setRequests(data.filter((r) => String(r.ownerEmail || '').trim().toLowerCase() === normalizedEmail));
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Não foi possível carregar pedidos.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [merchant?.id]);

  if (!merchant) {
    return (
      <div className="p-6 lg:p-8">
        <GlassCard className="p-8">
          <h2 className="text-xl font-semibold text-white mb-2">Crie sua loja primeiro</h2>
          <p className="text-white/60 mb-6">Você precisa de um perfil de lojista para gerar pedidos de pagamento.</p>
          <Button asChild className="bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-semibold">
            <Link to="/merchant/setup">Criar loja</Link>
          </Button>
        </GlassCard>
      </div>
    );
  }

  return (
    <div className="p-6 lg:p-8">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Pedidos de pagamento</h1>
          <p className="text-white/50">{merchant.displayName}</p>
        </div>
        <div className="flex gap-3">
          <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
            <Link to="/merchant">Voltar</Link>
          </Button>
          <Button asChild className="bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-semibold">
            <Link to="/merchant/requests/new">Novo pedido</Link>
          </Button>
        </div>
      </div>

      <GlassCard className="divide-y divide-white/5">
        {loading ? (
          <div className="p-6 text-white/50">Carregando...</div>
        ) : requests.length === 0 ? (
          <div className="p-6 text-white/50">
            Nenhum pedido ainda.{' '}
            <button className="text-emerald-400 hover:underline" onClick={() => navigate('/merchant/requests/new')}>
              Criar agora
            </button>
          </div>
        ) : (
          requests.map((r) => {
            const shareUrl = `${window.location.origin}/pay/${r.id}`;
            const expired = r.status === 'expired' || r.expiresAt <= Date.now();
            return (
              <div key={r.id} className="p-4 flex flex-col md:flex-row md:items-center justify-between gap-4">
                <div className="min-w-0">
                  <div className="text-white font-medium truncate">{r.memo || r.id}</div>
                  <div className="text-xs text-white/50">
                    criado {formatDate(r.createdAt)} • expira {formatDate(r.expiresAt)}
                  </div>
                  <div className="text-xs text-white/50 break-all">{shareUrl}</div>
                </div>
                <div className="flex items-center gap-3">
                  <div className="text-white font-semibold">
                    {r.denom === DENOM ? formatDenomAmount(r.amount, r.denom) : `${r.amount} ${r.denom}`}
                  </div>
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
          })
        )}
      </GlassCard>
    </div>
  );
}
