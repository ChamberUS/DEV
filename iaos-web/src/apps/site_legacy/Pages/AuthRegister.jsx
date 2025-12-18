import React, { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import GlassCard from '@/components/ui/GlassCard';
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Checkbox } from "@/components/ui/checkbox";
import { toast } from 'sonner';
import { registerUser } from '@/api/authClient';
import { getUserSession, isUserSessionValid } from '@/auth/userAuth';
import { useAuth } from '@/auth/AuthContext';

function sanitizeReturnTo(value) {
  if (typeof value !== 'string') return null;
  if (!value.startsWith('/')) return null;
  if (value.startsWith('/auth')) return null;
  return value;
}

export default function AuthRegister() {
  const navigate = useNavigate();
  const location = useLocation();
  const { refreshSessions } = useAuth();
  const [type, setType] = useState('personal');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const existing = getUserSession();
  if (isUserSessionValid(existing)) return <Navigate to="/account" replace />;

  const returnTo = sanitizeReturnTo(location.state?.returnTo) || '/account';

  const onSubmit = async (e) => {
    e.preventDefault();
    if (submitting) return;
    const normalizedEmail = email.trim();
    if (!normalizedEmail || !password) {
      toast.error('Preencha e-mail e senha.');
      return;
    }
    setSubmitting(true);
    try {
      await registerUser({ email: normalizedEmail, password, type, rememberMe });
      refreshSessions();
      toast.success('Conta criada com sucesso!');
      navigate(returnTo, { replace: true });
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Não foi possível criar a conta.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-6 relative overflow-hidden dark bg-[#070B0F] text-white">
      <div className="absolute inset-0 pointer-events-none">
        <div className="absolute top-1/4 left-1/4 w-96 h-96 bg-emerald-500/10 rounded-full blur-[120px]" />
        <div className="absolute bottom-1/4 right-1/4 w-96 h-96 bg-cyan-500/10 rounded-full blur-[120px]" />
      </div>

      <div className="w-full max-w-md relative z-10">
        <GlassCard className="p-8">
          <h1 className="text-2xl font-bold text-white mb-2">Criar uma conta</h1>
          <p className="text-white/50 mb-6">Cadastro mock no AIOS (sem backend por enquanto).</p>

          <div className="flex gap-2 mb-6">
            <Button
              type="button"
              onClick={() => setType('personal')}
              className={`flex-1 ${
                type === 'personal'
                  ? 'bg-gradient-to-r from-emerald-500 to-cyan-500'
                  : 'bg-white/5 text-white/60 hover:bg-white/10'
              }`}
            >
              Pessoal
            </Button>
            <Button
              type="button"
              onClick={() => setType('business')}
              className={`flex-1 ${
                type === 'business'
                  ? 'bg-gradient-to-r from-emerald-500 to-cyan-500'
                  : 'bg-white/5 text-white/60 hover:bg-white/10'
              }`}
            >
              Empresarial
            </Button>
          </div>

          <form onSubmit={onSubmit} className="space-y-4">
            <div>
              <Label className="text-white/70">E-mail</Label>
              <Input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="voce@exemplo.com"
                className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
              />
            </div>
            <div>
              <Label className="text-white/70">Senha</Label>
              <Input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
              />
            </div>

            <label className="flex items-center gap-2 text-sm text-white/60" htmlFor="rememberMe">
              <Checkbox
                id="rememberMe"
                checked={rememberMe}
                onCheckedChange={(checked) => setRememberMe(checked === true)}
              />
              Lembrar-me (7 dias)
            </label>

            <Button
              type="submit"
              disabled={submitting}
              className="w-full h-12 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-semibold"
            >
              {submitting ? 'Criando...' : 'Criar conta'}
            </Button>

            <p className="text-center text-white/40 text-sm">
              Já tem conta?{' '}
              <Link to="/auth/login" className="text-emerald-400 hover:text-emerald-300">
                Entrar
              </Link>
            </p>
          </form>
        </GlassCard>
      </div>
    </div>
  );
}
