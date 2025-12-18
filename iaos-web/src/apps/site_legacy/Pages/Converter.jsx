import React, { useState } from 'react';
import { useMutation, useQueryClient, useQuery } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion } from 'framer-motion';
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { ArrowDownUp, Calculator, TrendingUp, Zap, History } from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';
import { toast } from 'sonner';

const BYX_RATE = 0.95; // 1 BYX = R$ 0.95

export default function Converter() {
  const [amount, setAmount] = useState('');
  const [direction, setDirection] = useState('brl_to_byx'); // brl_to_byx or byx_to_brl
  const queryClient = useQueryClient();

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => base44.auth.me(),
  });

  const { data: wallets = [] } = useQuery({
    queryKey: ['wallets', user?.email],
    queryFn: () => base44.entities.Wallet.filter({ created_by: user?.email }),
    enabled: !!user?.email,
  });

  const wallet = wallets[0];

  const convertMutation = useMutation({
    mutationFn: async () => {
      const numAmount = parseFloat(amount);
      if (!numAmount || numAmount <= 0) throw new Error('Valor inválido');
      
      let byxAmount, brlAmount;
      if (direction === 'brl_to_byx') {
        brlAmount = numAmount;
        byxAmount = numAmount / BYX_RATE;
      } else {
        byxAmount = numAmount;
        brlAmount = numAmount * BYX_RATE;
      }

      // Create transaction
      await base44.entities.Transaction.create({
        type: 'convert',
        amount: byxAmount,
        description: `Conversão ${direction === 'brl_to_byx' ? 'BRL → BYX' : 'BYX → BRL'}`,
        from_user: user?.email,
        to_user: user?.email,
      });

      // Update wallet
      if (wallet) {
        const newBalance = direction === 'brl_to_byx' 
          ? wallet.balance + byxAmount
          : wallet.balance - byxAmount;
        const newBalanceBrl = newBalance * BYX_RATE;
        
        await base44.entities.Wallet.update(wallet.id, {
          balance: newBalance,
          balance_brl: newBalanceBrl,
        });
      }
    },
    onSuccess: () => {
      toast.success('Conversão realizada com sucesso!');
      queryClient.invalidateQueries({ queryKey: ['wallets'] });
      queryClient.invalidateQueries({ queryKey: ['transactions'] });
      setAmount('');
    },
    onError: (error) => {
      toast.error(error.message || 'Erro ao realizar conversão');
    },
  });

  const numAmount = parseFloat(amount) || 0;
  const convertedAmount = direction === 'brl_to_byx' 
    ? numAmount / BYX_RATE 
    : numAmount * BYX_RATE;

  const toggleDirection = () => {
    setDirection(prev => prev === 'brl_to_byx' ? 'byx_to_brl' : 'brl_to_byx');
    setAmount('');
  };

  return (
    <div className="p-6 lg:p-8 max-w-3xl mx-auto">
      {/* Header */}
      <motion.div 
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="text-center mb-8"
      >
        <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Conversor BYX</h1>
        <p className="text-white/50">Converta entre BRL e BYX instantaneamente</p>
      </motion.div>

      {/* Rate Info */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1 }}
        className="grid grid-cols-3 gap-4 mb-8"
      >
        <GlassCard className="p-4 text-center">
          <TrendingUp className="w-6 h-6 text-emerald-400 mx-auto mb-2" />
          <p className="text-white font-bold">R$ {BYX_RATE.toFixed(2)}</p>
          <p className="text-white/40 text-xs">1 BYX</p>
        </GlassCard>
        <GlassCard className="p-4 text-center">
          <Zap className="w-6 h-6 text-amber-400 mx-auto mb-2" />
          <p className="text-white font-bold">0%</p>
          <p className="text-white/40 text-xs">Taxa</p>
        </GlassCard>
        <GlassCard className="p-4 text-center">
          <History className="w-6 h-6 text-blue-400 mx-auto mb-2" />
          <p className="text-white font-bold">Instantâneo</p>
          <p className="text-white/40 text-xs">Tempo</p>
        </GlassCard>
      </motion.div>

      {/* Converter */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.2 }}
      >
        <GlassCard gradient className="p-8">
          <div className="space-y-6">
            {/* From */}
            <div>
              <Label className="text-white/60 text-sm mb-2 block">
                {direction === 'brl_to_byx' ? 'De (BRL)' : 'De (BYX)'}
              </Label>
              <div className="relative">
                <Input
                  type="number"
                  placeholder="0.00"
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                  className="text-3xl h-16 bg-white/5 border-white/10 text-white placeholder:text-white/20 pr-16"
                />
                <span className="absolute right-4 top-1/2 -translate-y-1/2 text-white/40 font-medium">
                  {direction === 'brl_to_byx' ? 'BRL' : 'BYX'}
                </span>
              </div>
            </div>

            {/* Toggle Button */}
            <div className="flex justify-center">
              <Button
                variant="outline"
                size="icon"
                onClick={toggleDirection}
                className="w-12 h-12 rounded-full border-white/20 bg-white/5 hover:bg-white/10 text-white"
              >
                <ArrowDownUp className="w-5 h-5" />
              </Button>
            </div>

            {/* To */}
            <div>
              <Label className="text-white/60 text-sm mb-2 block">
                {direction === 'brl_to_byx' ? 'Para (BYX)' : 'Para (BRL)'}
              </Label>
              <div className="relative">
                <div className="text-3xl h-16 bg-white/10 border border-white/10 rounded-md flex items-center px-4 text-emerald-400 font-bold">
                  {convertedAmount.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                </div>
                <span className="absolute right-4 top-1/2 -translate-y-1/2 text-white/40 font-medium">
                  {direction === 'brl_to_byx' ? 'BYX' : 'BRL'}
                </span>
              </div>
            </div>

            {/* Summary */}
            <div className="pt-4 border-t border-white/10">
              <div className="flex items-center justify-between text-sm mb-2">
                <span className="text-white/40">Taxa de conversão</span>
                <span className="text-white">1 BYX = R$ {BYX_RATE.toFixed(2)}</span>
              </div>
              <div className="flex items-center justify-between text-sm mb-4">
                <span className="text-white/40">Taxa de serviço</span>
                <span className="text-emerald-400">Grátis</span>
              </div>
            </div>

            {/* Convert Button */}
            <Button
              onClick={() => convertMutation.mutate()}
              disabled={!amount || convertMutation.isPending}
              className="w-full h-14 text-lg bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white border-0 shadow-lg shadow-emerald-500/30"
            >
              {convertMutation.isPending ? (
                <span className="flex items-center gap-2">
                  <div className="w-5 h-5 border-2 border-white/20 border-t-white rounded-full animate-spin" />
                  Convertendo...
                </span>
              ) : (
                <>
                  <Calculator className="w-5 h-5 mr-2" />
                  Converter Agora
                </>
              )}
            </Button>
          </div>
        </GlassCard>
      </motion.div>

      {/* Quick Amounts */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.3 }}
        className="mt-6"
      >
        <p className="text-white/40 text-sm text-center mb-3">Valores rápidos</p>
        <div className="flex flex-wrap justify-center gap-2">
          {[10, 50, 100, 500, 1000].map((value) => (
            <Button
              key={value}
              variant="outline"
              size="sm"
              onClick={() => setAmount(value.toString())}
              className="border-white/10 text-white/60 hover:text-white hover:bg-white/10"
            >
              {direction === 'brl_to_byx' ? 'R$' : ''} {value} {direction === 'byx_to_brl' ? 'BYX' : ''}
            </Button>
          ))}
        </div>
      </motion.div>
    </div>
  );
}