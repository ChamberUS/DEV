import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion } from 'framer-motion';
import { User, Briefcase, Wallet, Camera, Store, ArrowRight } from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { toast } from 'sonner';
import CreateStoreWizard from '@/apps/site_legacy/Components/store/CreateStoreWizard';

export default function MyAccount() {
  const [isEditingProfile, setIsEditingProfile] = useState(false);
  const [showStoreWizard, setShowStoreWizard] = useState(false);
  const [profileData, setProfileData] = useState({
    full_name: '',
    occupation: '',
  });

  const queryClient = useQueryClient();

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => base44.auth.me(),
  });

  const { data: stores = [] } = useQuery({
    queryKey: ['myStore', user?.email],
    queryFn: () => base44.entities.Store.filter({ owner_email: user?.email }),
    enabled: !!user?.email,
  });

  const { data: wallets = [] } = useQuery({
    queryKey: ['wallets', user?.email],
    queryFn: () => base44.entities.Wallet.filter({ created_by: user?.email }),
    enabled: !!user?.email,
  });

  const myStore = stores[0];
  const wallet = wallets[0] || { balance: 0 };

  // Generate nickname from wallet ID if not exists
  const nickname = user?.nickname || `IAOS${user?.id?.substring(0, 12) || ''}`;

  const updateProfileMutation = useMutation({
    mutationFn: (data) => base44.auth.updateMe(data),
    onSuccess: () => {
      toast.success('Perfil atualizado com sucesso!');
      queryClient.invalidateQueries({ queryKey: ['currentUser'] });
      setIsEditingProfile(false);
    },
  });

  const handleSaveProfile = () => {
    updateProfileMutation.mutate(profileData);
  };

  React.useEffect(() => {
    if (user) {
      setProfileData({
        full_name: user.full_name || '',
        occupation: user.occupation || '',
      });
    }
  }, [user]);

  return (
    <div className="min-h-screen bg-[#000000]">
      <div className="max-w-[1400px] mx-auto p-8 lg:p-12">
        {/* Header */}
        <motion.div 
          initial={{ opacity: 0, y: -20 }}
          animate={{ opacity: 1, y: 0 }}
          className="mb-8"
        >
          <h1 className="text-3xl md:text-4xl font-bold text-white mb-2">Minha Conta</h1>
          <p className="text-white/50">Gerencie suas informações pessoais e configurações</p>
        </motion.div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Profile Section */}
          <div className="lg:col-span-2 space-y-6">
            {/* Profile Photo & Basic Info */}
            <GlassCard className="p-6">
              <div className="flex items-start gap-6">
                <div className="relative group">
                  <div className="w-24 h-24 rounded-full bg-gradient-to-br from-[#4a9eff] to-[#1a4d2e] flex items-center justify-center text-white text-3xl font-bold">
                    {user?.profile_photo_url ? (
                      <img 
                        src={user.profile_photo_url} 
                        alt="Profile" 
                        className="w-full h-full rounded-full object-cover"
                      />
                    ) : (
                      user?.full_name?.charAt(0)?.toUpperCase() || 'U'
                    )}
                  </div>
                  <button className="absolute inset-0 rounded-full bg-black/50 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center">
                    <Camera className="w-6 h-6 text-white" />
                  </button>
                </div>
                <div className="flex-1">
                  <h2 className="text-2xl font-bold text-white mb-1">{user?.full_name || 'Usuário'}</h2>
                  {myStore && (
                    <p className="text-[#4a9eff] text-sm mb-2 underline">{myStore.name}</p>
                  )}
                  <p className="text-white/40 text-sm">{user?.email}</p>
                </div>
              </div>
            </GlassCard>

            {/* Personal Information */}
            <GlassCard className="p-6">
              <div className="flex items-center justify-between mb-6">
                <div className="flex items-center gap-3">
                  <User className="w-5 h-5 text-[#4a9eff]" />
                  <h3 className="text-white font-semibold">Minhas Informações</h3>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setIsEditingProfile(!isEditingProfile)}
                  className="text-[#4a9eff] hover:text-white"
                >
                  {isEditingProfile ? 'Cancelar' : 'Editar'}
                </Button>
              </div>

              {isEditingProfile ? (
                <div className="space-y-4">
                  <div>
                    <Label className="text-white/70">Nome Completo</Label>
                    <Input
                      value={profileData.full_name}
                      onChange={(e) => setProfileData({ ...profileData, full_name: e.target.value })}
                      className="bg-white/5 border-white/10 text-white"
                    />
                  </div>
                  <div>
                    <Label className="text-white/70">Ocupação</Label>
                    <Input
                      value={profileData.occupation}
                      onChange={(e) => setProfileData({ ...profileData, occupation: e.target.value })}
                      placeholder="Ex: Proprietário, Desenvolvedor, etc."
                      className="bg-white/5 border-white/10 text-white"
                    />
                  </div>
                  <Button
                    onClick={handleSaveProfile}
                    disabled={updateProfileMutation.isPending}
                    className="w-full bg-gradient-to-r from-[#4a9eff] to-[#1a4d2e]"
                  >
                    {updateProfileMutation.isPending ? 'Salvando...' : 'Salvar Alterações'}
                  </Button>
                </div>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div>
                    <p className="text-white/40 text-xs mb-1">Nome Real</p>
                    <p className="text-white">{user?.full_name || 'Não informado'}</p>
                  </div>
                  <div>
                    <p className="text-white/40 text-xs mb-1">Email</p>
                    <p className="text-white">{user?.email}</p>
                  </div>
                  <div>
                    <p className="text-white/40 text-xs mb-1">Nickname</p>
                    <p className="text-white font-mono">{nickname}</p>
                  </div>
                  <div>
                    <p className="text-white/40 text-xs mb-1">Ocupação</p>
                    <p className="text-white">{user?.occupation || 'Não informado'}</p>
                  </div>
                </div>
              )}
            </GlassCard>

            {/* Store Section */}
            {myStore ? (
              <GlassCard className="p-6 bg-gradient-to-br from-[#1a4d2e]/20 to-[#4a9eff]/10">
                <div className="flex items-center justify-between mb-4">
                  <div className="flex items-center gap-3">
                    <Store className="w-5 h-5 text-[#1a4d2e]" />
                    <h3 className="text-white font-semibold">Minha Loja</h3>
                  </div>
                  <Button
                    variant="outline"
                    size="sm"
                    className="border-[#1a4d2e]/50 text-white hover:bg-[#1a4d2e]/30"
                  >
                    Configurações
                  </Button>
                </div>
                <div className="flex items-center gap-4">
                  <div className="w-16 h-16 rounded-xl bg-gradient-to-br from-[#4a9eff] to-[#1a4d2e] flex items-center justify-center text-white text-2xl font-bold">
                    {myStore.name?.charAt(0)?.toUpperCase()}
                  </div>
                  <div>
                    <h4 className="text-white font-semibold text-lg">{myStore.name}</h4>
                    <p className="text-white/60 text-sm">{myStore.description || 'Sem descrição'}</p>
                  </div>
                </div>
              </GlassCard>
            ) : (
              <GlassCard className="p-8 bg-gradient-to-br from-[#1a4d2e]/20 to-[#4a9eff]/10 text-center">
                <Store className="w-16 h-16 text-[#1a4d2e] mx-auto mb-4" />
                <h3 className="text-2xl font-bold text-white mb-2">Crie uma Loja</h3>
                <p className="text-white/60 mb-6">Ganhe dinheiro vendendo seus produtos na IAOS</p>
                <Button
                  onClick={() => setShowStoreWizard(true)}
                  className="bg-gradient-to-r from-[#4a9eff] to-[#1a4d2e] hover:from-[#4a9eff]/90 hover:to-[#1a4d2e]/90"
                >
                  Criar Minha Loja <ArrowRight className="w-4 h-4 ml-2" />
                </Button>
              </GlassCard>
            )}
          </div>

          {/* Sidebar */}
          <div className="space-y-6">
            {/* Wallet Balance */}
            <GlassCard className="p-6">
              <div className="flex items-center gap-3 mb-4">
                <Wallet className="w-5 h-5 text-[#4a9eff]" />
                <h3 className="text-white font-semibold">Saldo IAOS</h3>
              </div>
              <div className="text-center py-4">
                <p className="text-4xl font-bold text-white mb-2">
                  {wallet.balance?.toLocaleString('pt-BR')} <span className="text-xl text-[#4a9eff]">IAOS</span>
                </p>
                <p className="text-white/40 text-sm">
                  ≈ R$ {(wallet.balance * 0.95)?.toLocaleString('pt-BR', { minimumFractionDigits: 2 })}
                </p>
              </div>
              <div className="grid grid-cols-2 gap-2 mt-4">
                <Button variant="outline" size="sm" className="border-white/10 text-white hover:bg-white/10">
                  Depositar
                </Button>
                <Button variant="outline" size="sm" className="border-white/10 text-white hover:bg-white/10">
                  Sacar
                </Button>
              </div>
            </GlassCard>

            {/* Quick Stats */}
            <GlassCard className="p-6">
              <h3 className="text-white font-semibold mb-4">Fontes de Renda</h3>
              <div className="space-y-3">
                <div className="flex justify-between items-center py-2">
                  <span className="text-white/60 text-sm">Cashback</span>
                  <span className="text-white font-semibold">150 IAOS</span>
                </div>
                <div className="flex justify-between items-center py-2">
                  <span className="text-white/60 text-sm">Vendas</span>
                  <span className="text-white font-semibold">850 IAOS</span>
                </div>
                <div className="flex justify-between items-center py-2">
                  <span className="text-white/60 text-sm">Earn</span>
                  <span className="text-white font-semibold">200 IAOS</span>
                </div>
                <div className="flex justify-between items-center py-2">
                  <span className="text-white/60 text-sm">Trading</span>
                  <span className="text-white font-semibold">300 IAOS</span>
                </div>
              </div>
            </GlassCard>

            {/* Account Role */}
            <GlassCard className="p-6">
              <div className="flex items-center gap-3 mb-4">
                <Briefcase className="w-5 h-5 text-[#1a4d2e]" />
                <h3 className="text-white font-semibold">Tipo de Conta</h3>
              </div>
              <div className="text-center py-2">
                <p className="text-white font-medium capitalize">{user?.role || 'Usuário'}</p>
                <p className="text-white/40 text-xs mt-1">
                  {myStore ? 'Lojista Verificado' : 'Cliente'}
                </p>
              </div>
            </GlassCard>
          </div>
        </div>
      </div>

      {/* Store Creation Wizard */}
      {showStoreWizard && (
        <CreateStoreWizard
          user={user}
          onClose={() => setShowStoreWizard(false)}
          onComplete={() => {
            setShowStoreWizard(false);
            queryClient.invalidateQueries({ queryKey: ['myStore'] });
          }}
        />
      )}
    </div>
  );
}