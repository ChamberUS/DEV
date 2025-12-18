import React from 'react';
import { motion } from 'framer-motion';
import { Vault } from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';

export default function FullStake() {
  return (
    <div className="p-6 lg:p-8">
      <motion.div 
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="mb-8"
      >
        <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Full Stake</h1>
        <p className="text-white/50">Opções avançadas de staking</p>
      </motion.div>

      <GlassCard className="p-12">
        <div className="text-center py-16">
          <Vault className="w-16 h-16 text-white/20 mx-auto mb-4" />
          <h3 className="text-white/60 text-lg mb-2">Full Stake em desenvolvimento</h3>
          <p className="text-white/40 text-sm">Em breve você terá acesso a mais opções de staking</p>
        </div>
      </GlassCard>
    </div>
  );
}