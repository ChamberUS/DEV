import React from 'react';
import { motion } from 'framer-motion';
import { Wallet, ArrowDownLeft, ArrowUpRight, Sparkles } from 'lucide-react';
import GlassCard from '../ui/GlassCard';

export default function WalletCard({ balance = 0, balanceBrl = 0, receivedMonth = 0, sentMonth = 0 }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.5 }}
    >
      <GlassCard gradient className="p-6 relative overflow-hidden">
        {/* Background decoration */}
        <div className="absolute top-0 right-0 w-64 h-64 bg-emerald-400/10 rounded-full blur-3xl -translate-y-1/2 translate-x-1/2" />
        <div className="absolute bottom-0 left-0 w-48 h-48 bg-blue-400/10 rounded-full blur-3xl translate-y-1/2 -translate-x-1/2" />
        
        <div className="relative z-10">
          <div className="flex items-center justify-between mb-6">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-emerald-400 to-cyan-400 flex items-center justify-center">
                <Wallet className="w-5 h-5 text-white" />
              </div>
              <span className="text-white/70 font-medium">Carteira BYX</span>
            </div>
            <motion.div 
              animate={{ rotate: [0, 15, -15, 0] }}
              transition={{ duration: 2, repeat: Infinity, repeatDelay: 3 }}
            >
              <Sparkles className="w-5 h-5 text-emerald-400" />
            </motion.div>
          </div>

          <div className="mb-8">
            <motion.h2 
              className="text-4xl md:text-5xl font-bold text-white mb-2"
              initial={{ scale: 0.9 }}
              animate={{ scale: 1 }}
              transition={{ duration: 0.3 }}
            >
              {balance.toLocaleString('pt-BR', { minimumFractionDigits: 2 })} <span className="text-emerald-400">BYX</span>
            </motion.h2>
            <p className="text-white/50 text-sm">
              ≈ R$ {balanceBrl.toLocaleString('pt-BR', { minimumFractionDigits: 2 })}
            </p>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="bg-white/5 rounded-xl p-4 border border-white/5">
              <div className="flex items-center gap-2 mb-1">
                <ArrowDownLeft className="w-4 h-4 text-emerald-400" />
                <span className="text-white/50 text-xs">Recebido (mês)</span>
              </div>
              <p className="text-white font-semibold">{receivedMonth.toLocaleString('pt-BR', { minimumFractionDigits: 2 })} BYX</p>
            </div>
            <div className="bg-white/5 rounded-xl p-4 border border-white/5">
              <div className="flex items-center gap-2 mb-1">
                <ArrowUpRight className="w-4 h-4 text-rose-400" />
                <span className="text-white/50 text-xs">Enviado (mês)</span>
              </div>
              <p className="text-white font-semibold">{sentMonth.toLocaleString('pt-BR', { minimumFractionDigits: 2 })} BYX</p>
            </div>
          </div>
        </div>
      </GlassCard>
    </motion.div>
  );
}