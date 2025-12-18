import React, { useMemo } from 'react';
import { Link, Navigate } from 'react-router-dom';
import GlassCard from '@/components/ui/GlassCard';
import { Button } from "@/components/ui/button";
import { useAuth } from '@/auth/AuthContext';
import { getMerchantByEmail } from '@/merchants/merchantStore';

export default function MerchantDashboard() {
  const { userSession } = useAuth();
  const email = userSession?.email ?? null;
  if (!email) return <Navigate to="/auth/login" replace />;

  const merchant = useMemo(() => getMerchantByEmail(email), [email]);

  return (
    <div className="p-6 lg:p-8">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Área do Lojista</h1>
          <p className="text-white/50">Gerencie sua loja e pedidos de pagamento (MVP local-first).</p>
        </div>
        {merchant && (
          <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
            <Link to="/merchant/requests/new">Novo pedido</Link>
          </Button>
        )}
      </div>

      {!merchant ? (
        <GlassCard className="p-8">
          <h2 className="text-xl font-semibold text-white mb-2">Você ainda não tem uma loja</h2>
          <p className="text-white/60 mb-6">Crie seu perfil de lojista para gerar links de pagamento e uma página pública.</p>
          <Button
            asChild
            className="h-12 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-semibold"
          >
            <Link to="/merchant/setup">Criar minha loja</Link>
          </Button>
        </GlassCard>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <GlassCard className="p-6 space-y-2">
            <div className="flex items-start justify-between gap-4">
              <div>
                <h2 className="text-lg font-semibold text-white">{merchant.displayName}</h2>
                <p className="text-white/50 text-sm">{merchant.category}</p>
              </div>
              <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
                <Link to="/merchant/setup">Editar loja</Link>
              </Button>
            </div>
            {merchant.description && <p className="text-white/70 text-sm">{merchant.description}</p>}
            <div className="text-sm text-white/60 pt-3 border-t border-white/10">
              <div className="break-all"><span className="text-white/40">Endereço BYX:</span> {merchant.byxAddress}</div>
            </div>
          </GlassCard>

          <GlassCard className="p-6 space-y-3">
            <h2 className="text-lg font-semibold text-white">Ações</h2>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
                <Link to="/merchant/requests">Pedidos</Link>
              </Button>
              <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
                <Link to={`/store/${merchant.id}`}>Página pública</Link>
              </Button>
              <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
                <Link to="/merchant/requests/new">Novo pedido</Link>
              </Button>
            </div>
            <p className="text-white/50 text-sm">
              Os pedidos são criados localmente e podem ser compartilhados por link/QR. Integração on-chain é tentativa + fallback.
            </p>
          </GlassCard>
        </div>
      )}
    </div>
  );
}

