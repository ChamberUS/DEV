import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion } from 'framer-motion';
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Vault, TrendingUp, Clock, Lock, Unlock, DollarSign } from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';
import { Badge } from "@/components/ui/badge";
import { toast } from 'sonner';
import moment from 'moment';
import 'moment/locale/pt-br';

moment.locale('pt-br');

const stakingPlans = [
  { days: 30, apy: 8, label: '30 dias', color: 'from-blue-500 to-cyan-500' },
  { days: 90, apy: 12, label: '90 dias', color: 'from-purple-500 to-pink-500' },
  { days: 180, apy: 18, label: '180 dias', color: 'from-orange-500 to-red-500' },
  { days: 365, apy: 25, label: '365 dias', color: 'from-emerald-500 to-cyan-500' },
];

export default function Earn() {
  const [selectedPlan, setSelectedPlan] = useState(stakingPlans[0]);
  const [amount, setAmount] = useState('');
  const queryClient = useQueryClient();

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => base44.auth.me(),
  });

  const { data: stakingPools = [] } = useQuery({
    queryKey: ['stakingPools', user?.email],
    queryFn: () => base44.entities.StakingPool.filter(
      { user_email: user?.email },
      '-created_date'
    ),
    enabled: !!user?.email,
  });

  const { data: wallets = [] } = useQuery({
    queryKey: ['wallets', user?.email],
    queryFn: () => base44.entities.Wallet.filter({ created_by: user?.email }),
    enabled: !!user?.email,
  });

  const wallet = wallets[0];

  const stakeMutation = useMutation({
    mutationFn: async () => {
      const numAmount = parseFloat(amount);
      if (!numAmount || numAmount <= 0) throw new Error('Valor inválido');
      if (!wallet || wallet.balance < numAmount) throw new Error('Saldo insuficiente');

      const startDate = new Date();
      const endDate = moment(startDate).add(selectedPlan.days, 'days').toDate();

      // Create staking pool
      await base44.entities.StakingPool.create({
        user_email: user?.email,
        amount_byx: numAmount,
        lock_period_days: selectedPlan.days,
        apy_percentage: selectedPlan.apy,
        start_date: startDate.toISOString(),
        end_date: endDate.toISOString(),
        status: 'active',
        rewards_earned: 0,
      });

      // Update wallet balance
      await base44.entities.Wallet.update(wallet.id, {
        balance: wallet.balance - numAmount,
        balance_brl: (wallet.balance - numAmount) * 0.95,
      });
    },
    onSuccess: () => {
      toast.success('BYX depositado com sucesso!');
      queryClient.invalidateQueries({ queryKey: ['stakingPools'] });
      queryClient.invalidateQueries({ queryKey: ['wallets'] });
      setAmount('');
    },
    onError: (error) => {
      toast.error(error.message || 'Erro ao depositar');
    },
  });

  const withdrawMutation = useMutation({
    mutationFn: async (pool) => {
      const now = new Date();
      const endDate = new Date(pool.end_date);
      
      if (now < endDate) {
        throw new Error('Período de bloqueio ainda não terminou');
      }

      // Calculate rewards
      const daysElapsed = moment(now).diff(moment(pool.start_date), 'days');
      const rewards = (pool.amount_byx * pool.apy_percentage / 100 / 365) * daysElapsed;
      const totalAmount = pool.amount_byx + rewards;

      // Update pool
      await base44.entities.StakingPool.update(pool.id, {
        status: 'withdrawn',
        rewards_earned: rewards,
      });

      // Update wallet
      if (wallet) {
        await base44.entities.Wallet.update(wallet.id, {
          balance: wallet.balance + totalAmount,
          balance_brl: (wallet.balance + totalAmount) * 0.95,
        });
      }

      return { totalAmount, rewards };
    },
    onSuccess: ({ totalAmount, rewards }) => {
      toast.success(`Resgatado ${totalAmount.toFixed(2)} BYX (${rewards.toFixed(2)} BYX de lucro)!`);
      queryClient.invalidateQueries({ queryKey: ['stakingPools'] });
      queryClient.invalidateQueries({ queryKey: ['wallets'] });
    },
    onError: (error) => {
      toast.error(error.message || 'Erro ao resgatar');
    },
  });

  const activePools = stakingPools.filter(p => p.status === 'active');
  const totalStaked = activePools.reduce((acc, p) => acc + (p.amount_byx || 0), 0);
  const estimatedRewards = activePools.reduce((acc, p) => {
    const daysElapsed = moment().diff(moment(p.start_date), 'days');
    return acc + (p.amount_byx * p.apy_percentage / 100 / 365) * daysElapsed;
  }, 0);

  return (
    <div className="p-6 lg:p-8">
      {/* Header */}
      <motion.div 
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="mb-8"
      >
        <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Earn - Staking BYX</h1>
        <p className="text-white/50">Deposite BYX e ganhe recompensas ao longo do tempo</p>
      </motion.div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Staking Panel */}
        <div className="lg:col-span-2 space-y-6">
          {/* Stats */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <GlassCard className="p-4">
              <div className="flex items-center gap-3 mb-2">
                <Vault className="w-8 h-8 text-emerald-400" />
                <div>
                  <p className="text-white/40 text-xs">Total em Staking</p>
                  <p className="text-white font-bold text-lg">{totalStaked.toFixed(2)} BYX</p>
                </div>
              </div>
            </GlassCard>
            <GlassCard className="p-4">
              <div className="flex items-center gap-3 mb-2">
                <TrendingUp className="w-8 h-8 text-amber-400" />
                <div>
                  <p className="text-white/40 text-xs">Recompensas Estimadas</p>
                  <p className="text-amber-400 font-bold text-lg">{estimatedRewards.toFixed(2)} BYX</p>
                </div>
              </div>
            </GlassCard>
            <GlassCard className="p-4">
              <div className="flex items-center gap-3 mb-2">
                <DollarSign className="w-8 h-8 text-cyan-400" />
                <div>
                  <p className="text-white/40 text-xs">Saldo Disponível</p>
                  <p className="text-white font-bold text-lg">{wallet?.balance.toFixed(2) || '0.00'} BYX</p>
                </div>
              </div>
            </GlassCard>
          </div>

          {/* Staking Plans */}
          <GlassCard className="p-6">
            <h3 className="text-white font-semibold mb-4">Escolha um Plano</h3>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-6">
              {stakingPlans.map((plan) => (
                <button
                  key={plan.days}
                  onClick={() => setSelectedPlan(plan)}
                  className={`p-5 rounded-xl transition-all ${
                    selectedPlan.days === plan.days
                      ? `bg-gradient-to-r ${plan.color} bg-opacity-20 border-2 border-current`
                      : 'bg-white/5 border border-white/10 hover:bg-white/10'
                  }`}
                >
                  <div className="text-left">
                    <p className="text-white font-bold text-2xl mb-1">{plan.apy}% APY</p>
                    <p className="text-white/60 text-sm mb-3">{plan.label} de bloqueio</p>
                    <div className="flex items-center gap-2 text-xs">
                      <Clock className="w-3 h-3 text-white/40" />
                      <span className="text-white/40">Bloqueio: {plan.days} dias</span>
                    </div>
                  </div>
                </button>
              ))}
            </div>

            <div className="space-y-4">
              <div>
                <Label className="text-white/70 mb-2 block">Valor para Depositar (BYX)</Label>
                <Input
                  type="number"
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                  placeholder="0.00"
                  className="bg-white/5 border-white/10 text-white placeholder:text-white/30 h-12 text-lg"
                />
              </div>

              <div className="bg-white/5 rounded-lg p-4 space-y-2">
                <div className="flex justify-between text-sm">
                  <span className="text-white/50">Período de bloqueio</span>
                  <span className="text-white font-semibold">{selectedPlan.days} dias</span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-white/50">APY</span>
                  <span className="text-emerald-400 font-semibold">{selectedPlan.apy}%</span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-white/50">Recompensa estimada</span>
                  <span className="text-amber-400 font-semibold">
                    {amount ? `+${(parseFloat(amount) * selectedPlan.apy / 100 / 365 * selectedPlan.days).toFixed(2)} BYX` : '0.00 BYX'}
                  </span>
                </div>
                <div className="flex justify-between text-sm pt-2 border-t border-white/10">
                  <span className="text-white/50">Total ao final</span>
                  <span className="text-white font-bold">
                    {amount ? `${(parseFloat(amount) * (1 + selectedPlan.apy / 100 / 365 * selectedPlan.days)).toFixed(2)} BYX` : '0.00 BYX'}
                  </span>
                </div>
              </div>

              <Button
                onClick={() => stakeMutation.mutate()}
                disabled={!amount || stakeMutation.isPending}
                className="w-full h-12 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-semibold"
              >
                <Vault className="w-5 h-5 mr-2" />
                {stakeMutation.isPending ? 'Depositando...' : 'Depositar BYX'}
              </Button>
            </div>
          </GlassCard>
        </div>

        {/* Active Stakes */}
        <div>
          <GlassCard className="p-6">
            <h3 className="text-white font-semibold mb-4">Seus Depósitos</h3>
            <div className="space-y-3">
              {activePools.length === 0 ? (
                <div className="text-center py-8 text-white/40 text-sm">
                  Nenhum depósito ativo
                </div>
              ) : (
                activePools.map((pool) => {
                  const now = moment();
                  const endDate = moment(pool.end_date);
                  const canWithdraw = now.isAfter(endDate);
                  const daysRemaining = endDate.diff(now, 'days');
                  const daysElapsed = now.diff(moment(pool.start_date), 'days');
                  const currentRewards = (pool.amount_byx * pool.apy_percentage / 100 / 365) * daysElapsed;

                  return (
                    <div key={pool.id} className="p-4 rounded-xl bg-white/5 border border-white/10">
                      <div className="flex items-center justify-between mb-3">
                        <div>
                          <p className="text-white font-bold">{pool.amount_byx.toFixed(2)} BYX</p>
                          <p className="text-white/40 text-xs">{pool.apy_percentage}% APY</p>
                        </div>
                        <Badge className={`${canWithdraw ? 'bg-emerald-400/20 text-emerald-400' : 'bg-amber-400/20 text-amber-400'}`}>
                          {canWithdraw ? <Unlock className="w-3 h-3 mr-1" /> : <Lock className="w-3 h-3 mr-1" />}
                          {canWithdraw ? 'Disponível' : `${daysRemaining}d`}
                        </Badge>
                      </div>
                      
                      <div className="mb-3">
                        <div className="flex justify-between text-xs text-white/40 mb-1">
                          <span>Progresso</span>
                          <span>{Math.min(100, (daysElapsed / pool.lock_period_days * 100)).toFixed(0)}%</span>
                        </div>
                        <div className="h-1.5 bg-white/5 rounded-full overflow-hidden">
                          <div 
                            className="h-full bg-gradient-to-r from-emerald-500 to-cyan-500 transition-all"
                            style={{ width: `${Math.min(100, (daysElapsed / pool.lock_period_days * 100))}%` }}
                          />
                        </div>
                      </div>

                      <div className="flex justify-between items-center text-xs mb-3">
                        <span className="text-white/40">Recompensas atuais</span>
                        <span className="text-amber-400 font-semibold">+{currentRewards.toFixed(2)} BYX</span>
                      </div>

                      {canWithdraw && (
                        <Button
                          onClick={() => withdrawMutation.mutate(pool)}
                          disabled={withdrawMutation.isPending}
                          size="sm"
                          className="w-full bg-emerald-500 hover:bg-emerald-600"
                        >
                          <Unlock className="w-4 h-4 mr-2" />
                          Resgatar
                        </Button>
                      )}
                    </div>
                  );
                })
              )}
            </div>
          </GlassCard>
        </div>
      </div>
    </div>
  );
}