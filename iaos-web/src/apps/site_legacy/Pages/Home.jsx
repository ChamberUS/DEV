import React, { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Wallet, Users, Building2 } from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';
import { useNavigate } from 'react-router-dom';
import { createPageUrl } from '@/utils/siteLegacy';
import { toast } from 'sonner';
import UserMenu from '@/components/UserMenu';
import ProfileMenu from '@/components/ProfileMenu';
import { useAuth } from '@/auth/AuthContext';
import { registerUser } from '@/api/authClient';

export default function Home() {
  const [accountType, setAccountType] = useState('personal');
  const [submitting, setSubmitting] = useState(false);
  const [formData, setFormData] = useState({
    name: '',
    email: '',
    password: '',
    company_name: '',
    city: '',
  });
  const { adminSession, userSession, refreshSessions } = useAuth();
  const hasValidAdminSession = Boolean(adminSession);
  const hasValidUserSession = Boolean(userSession);
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (submitting) return;
    
    if (accountType === 'personal') {
      if (!formData.name || !formData.email || !formData.password) {
        toast.error('Preencha todos os campos');
        return;
      }
    } else {
      if (!formData.company_name || !formData.email || !formData.name || !formData.city || !formData.password) {
        toast.error('Preencha todos os campos');
        return;
      }
    }

    setSubmitting(true);
    try {
      await registerUser({
        email: formData.email,
        password: formData.password,
        type: accountType === 'business' ? 'business' : 'personal',
        rememberMe: true,
      });
      refreshSessions();
      toast.success('Conta criada com sucesso!');
      navigate(createPageUrl('Dashboard'));
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Não foi possível criar a conta.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-6 relative overflow-hidden">
      {/* Background Effects */}
      <div className="absolute inset-0 pointer-events-none">
        <div className="absolute top-1/4 left-1/4 w-96 h-96 bg-emerald-500/10 rounded-full blur-[120px]" />
        <div className="absolute bottom-1/4 right-1/4 w-96 h-96 bg-cyan-500/10 rounded-full blur-[120px]" />
      </div>

      <div className="w-full max-w-6xl relative z-10">
        <motion.div
          initial={{ opacity: 0, y: -20 }}
          animate={{ opacity: 1, y: 0 }}
          className="text-center mb-8"
        >
          <div className="flex items-center justify-center gap-3 mb-4">
            <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-emerald-400 to-cyan-400 flex items-center justify-center">
              <Wallet className="w-8 h-8 text-white" />
            </div>
            <h1 className="text-4xl md:text-5xl font-bold text-white">AIOS</h1>
          </div>
          <p className="text-white/60 text-lg">A moeda do novo comércio digital</p>
          <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
            <Button
              type="button"
              onClick={() => navigate('/marketplace')}
              className="h-12 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-semibold px-6"
            >
              Acessar Marketplace
            </Button>
            <Button
              type="button"
              onClick={() => navigate('/wallet')}
              className="h-12 bg-white/5 text-white/80 hover:bg-white/10 px-6"
            >
              Carteira
            </Button>
            <Button
              type="button"
              onClick={() => navigate('/transactions')}
              className="h-12 bg-white/5 text-white/80 hover:bg-white/10 px-6"
            >
              Transações
            </Button>
            <Button
              type="button"
              onClick={() => navigate('/network')}
              className="h-12 bg-white/5 text-white/80 hover:bg-white/10 px-6"
            >
              Status da Rede
            </Button>
            {!hasValidAdminSession && hasValidUserSession && (
              <Button
                type="button"
                onClick={() => navigate('/account')}
                className="h-12 bg-white/5 text-white/80 hover:bg-white/10 px-6"
              >
                Ir para minha conta
              </Button>
            )}
            {hasValidAdminSession && (
              <Button
                type="button"
                onClick={() => navigate('/admin')}
                className="h-12 bg-white/5 text-white/80 hover:bg-white/10 px-6"
              >
                Ir para Admin
              </Button>
            )}
          </div>
        </motion.div>

        <GlassCard className="overflow-hidden">
          <div className={`grid grid-cols-1 lg:grid-cols-2 gap-0`}>
            {/* Form Section */}
            <motion.div
              animate={{ order: accountType === 'personal' ? 0 : 1 }}
              transition={{ duration: 0.5 }}
              className="p-8 md:p-12"
            >
              <h2 className="text-2xl font-bold text-white mb-2">Criar uma conta</h2>
              <p className="text-white/50 mb-6">Junte-se à revolução AIOS</p>

              {/* Account Type Selector */}
              <div className="flex gap-2 mb-6">
                <Button
                  onClick={() => setAccountType('personal')}
                  className={`flex-1 ${
                    accountType === 'personal'
                      ? 'bg-gradient-to-r from-emerald-500 to-cyan-500'
                      : 'bg-white/5 text-white/60 hover:bg-white/10'
                  }`}
                >
                  <Users className="w-4 h-4 mr-2" />
                  Pessoal
                </Button>
                <Button
                  onClick={() => setAccountType('business')}
                  className={`flex-1 ${
                    accountType === 'business'
                      ? 'bg-gradient-to-r from-emerald-500 to-cyan-500'
                      : 'bg-white/5 text-white/60 hover:bg-white/10'
                  }`}
                >
                  <Building2 className="w-4 h-4 mr-2" />
                  Empresarial
                </Button>
              </div>

              <form onSubmit={handleSubmit} className="space-y-4">
                <AnimatePresence mode="wait">
                  {accountType === 'personal' ? (
                    <motion.div
                      key="personal"
                      initial={{ opacity: 0, x: -20 }}
                      animate={{ opacity: 1, x: 0 }}
                      exit={{ opacity: 0, x: 20 }}
                      className="space-y-4"
                    >
                      <div>
                        <Label className="text-white/70">Nome</Label>
                        <Input
                          value={formData.name}
                          onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                          placeholder="Seu nome completo"
                          className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
                        />
                      </div>
                      <div>
                        <Label className="text-white/70">E-mail</Label>
                        <Input
                          type="email"
                          value={formData.email}
                          onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                          placeholder="seu@email.com"
                          className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
                        />
                      </div>
                      <div>
                        <Label className="text-white/70">Senha</Label>
                        <Input
                          type="password"
                          value={formData.password}
                          onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                          placeholder="••••••••"
                          className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
                        />
                      </div>
                    </motion.div>
                  ) : (
                    <motion.div
                      key="business"
                      initial={{ opacity: 0, x: -20 }}
                      animate={{ opacity: 1, x: 0 }}
                      exit={{ opacity: 0, x: 20 }}
                      className="space-y-4"
                    >
                      <div>
                        <Label className="text-white/70">Nome da Empresa</Label>
                        <Input
                          value={formData.company_name}
                          onChange={(e) => setFormData({ ...formData, company_name: e.target.value })}
                          placeholder="Nome da sua empresa"
                          className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
                        />
                      </div>
                      <div>
                        <Label className="text-white/70">E-mail</Label>
                        <Input
                          type="email"
                          value={formData.email}
                          onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                          placeholder="contato@empresa.com"
                          className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
                        />
                      </div>
                      <div>
                        <Label className="text-white/70">Senha</Label>
                        <Input
                          type="password"
                          value={formData.password}
                          onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                          placeholder="••••••••"
                          className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
                        />
                      </div>
                      <div>
                        <Label className="text-white/70">Nome do Responsável</Label>
                        <Input
                          value={formData.name}
                          onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                          placeholder="Nome completo"
                          className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
                        />
                      </div>
                      <div>
                        <Label className="text-white/70">Cidade</Label>
                        <Input
                          value={formData.city}
                          onChange={(e) => setFormData({ ...formData, city: e.target.value })}
                          placeholder="Sua cidade"
                          className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
                        />
                      </div>
                    </motion.div>
                  )}
                </AnimatePresence>

                <Button
                  type="submit"
                  disabled={submitting}
                  className="w-full h-12 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-semibold"
                >
                  {submitting ? 'Criando...' : `Criar conta ${accountType === 'personal' ? 'pessoal' : 'empresarial'}`}
                </Button>

                <div className="text-center">
                  <p className="text-white/40 text-sm">
                    Já tem uma conta?{' '}
                    {hasValidAdminSession && (
                      <UserMenu
                        label="Admin"
                        triggerVariant="inline"
                        variant="legacy"
                        contentClassName="bg-[#0a0a0a] border-[#1a4d2e]/50"
                      />
                    )}
                    {!hasValidAdminSession && hasValidUserSession && (
                      <ProfileMenu
                        label="Perfil"
                        variant="legacy"
                      />
                    )}
                    {!hasValidAdminSession && !hasValidUserSession && (
                      <button
                        type="button"
                        onClick={() => navigate('/auth/login')}
                        className="text-emerald-400 hover:text-emerald-300"
                      >
                        Entrar
                      </button>
                    )}
                  </p>
                </div>
              </form>
            </motion.div>

            {/* Image Section */}
            <motion.div
              animate={{ order: accountType === 'personal' ? 1 : 0 }}
              transition={{ duration: 0.5 }}
              className="relative bg-gradient-to-br from-emerald-500/20 to-cyan-500/20 hidden lg:block"
            >
              <AnimatePresence mode="wait">
                {accountType === 'personal' ? (
                  <motion.div
                    key="personal-image"
                    initial={{ opacity: 0, scale: 0.9 }}
                    animate={{ opacity: 1, scale: 1 }}
                    exit={{ opacity: 0, scale: 0.9 }}
                    transition={{ duration: 0.5 }}
                    className="absolute inset-0 flex items-center justify-center p-12"
                  >
                    <div className="text-center">
                      <div className="w-32 h-32 mx-auto mb-6 rounded-full bg-gradient-to-br from-emerald-400 to-cyan-400 flex items-center justify-center">
                        <Users className="w-16 h-16 text-white" />
                      </div>
                      <h3 className="text-2xl font-bold text-white mb-3">Conta Pessoal</h3>
                      <p className="text-white/70 leading-relaxed">
                        Compre produtos, gerencie sua carteira digital e participe do marketplace AIOS
                      </p>
                    </div>
                  </motion.div>
                ) : (
                  <motion.div
                    key="business-image"
                    initial={{ opacity: 0, scale: 0.9 }}
                    animate={{ opacity: 1, scale: 1 }}
                    exit={{ opacity: 0, scale: 0.9 }}
                    transition={{ duration: 0.5 }}
                    className="absolute inset-0 flex items-center justify-center p-12"
                  >
                    <div className="text-center">
                      <div className="w-32 h-32 mx-auto mb-6 rounded-full bg-gradient-to-br from-blue-400 to-indigo-400 flex items-center justify-center">
                        <Building2 className="w-16 h-16 text-white" />
                      </div>
                      <h3 className="text-2xl font-bold text-white mb-3">Conta Empresarial</h3>
                      <p className="text-white/70 leading-relaxed">
                        Venda seus produtos, gerencie sua loja e expanda seus negócios no ecossistema AIOS
                      </p>
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>
            </motion.div>
          </div>
        </GlassCard>
      </div>
    </div>
  );
}
