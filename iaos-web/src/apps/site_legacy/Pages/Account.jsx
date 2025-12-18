import React from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import GlassCard from '@/components/ui/GlassCard';
import { Button } from "@/components/ui/button";
import { useAuth } from '@/auth/AuthContext';

export default function Account() {
  const navigate = useNavigate();
  const { userSession, adminSession, clearUser } = useAuth();

  if (!userSession) return <Navigate to="/auth/login" replace />;

  const typeLabel = userSession.type === 'business' ? 'Empresarial' : 'Pessoal';
  const expLabel = new Date(userSession.exp).toLocaleString();
  const hasValidAdminSession = Boolean(adminSession);

  return (
    <div className="min-h-screen p-6 dark bg-[#070B0F] text-white">
      <div className="w-full max-w-5xl mx-auto space-y-6">
        <GlassCard className="p-8">
          <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4">
            <div>
              <h1 className="text-2xl font-bold text-white mb-2">Minha conta</h1>
              <div className="space-y-1">
                <p className="text-white/70">
                  <span className="text-white/40">E-mail:</span> {userSession.email}
                </p>
                <p className="text-white/70">
                  <span className="text-white/40">Tipo:</span> {typeLabel}
                </p>
                <p className="text-white/70">
                  <span className="text-white/40">Sessão expira em:</span> {expLabel}
                </p>
              </div>
            </div>

            <div className="flex gap-3">
              <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
                <Link to="/dashboard">Dashboard</Link>
              </Button>
              {hasValidAdminSession && (
                <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
                  <Link to="/admin">Admin Dashboard</Link>
                </Button>
              )}
              <Button
                type="button"
                variant="destructive"
                onClick={() => {
                  clearUser();
                  navigate('/', { replace: true });
                }}
              >
                Sair
              </Button>
            </div>
          </div>
        </GlassCard>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <GlassCard className="p-6">
            <h2 className="text-lg font-semibold text-white mb-1">Marketplace</h2>
            <p className="text-white/50 text-sm mb-4">Ver produtos e ofertas.</p>
            <Button asChild className="w-full bg-white/5 text-white/80 hover:bg-white/10">
              <Link to="/marketplace">Ir para Marketplace</Link>
            </Button>
          </GlassCard>

          <GlassCard className="p-6">
            <h2 className="text-lg font-semibold text-white mb-1">Área do lojista</h2>
            <p className="text-white/50 text-sm mb-4">Criar loja e gerar pedidos de pagamento.</p>
            <Button asChild className="w-full bg-white/5 text-white/80 hover:bg-white/10">
              <Link to="/merchant">Abrir área do lojista</Link>
            </Button>
          </GlassCard>

          <GlassCard className="p-6">
            <h2 className="text-lg font-semibold text-white mb-1">Minha carteira</h2>
            <p className="text-white/50 text-sm mb-4">Acessar carteira (legacy).</p>
            <Button asChild className="w-full bg-white/5 text-white/80 hover:bg-white/10">
              <Link to="/wallet">Abrir carteira</Link>
            </Button>
          </GlassCard>

          <GlassCard className="p-6">
            <h2 className="text-lg font-semibold text-white mb-1">Transações</h2>
            <p className="text-white/50 text-sm mb-4">Ver transações (legacy).</p>
            <Button asChild className="w-full bg-white/5 text-white/80 hover:bg-white/10">
              <Link to="/transactions">Ver transações</Link>
            </Button>
          </GlassCard>
        </div>
      </div>
    </div>
  );
}
