import React from 'react';
import { motion } from 'framer-motion';
import { TrendingUp, Users, ShoppingBag, CheckCircle } from 'lucide-react';
import GlassCard from '../ui/GlassCard';

const stats = [
  { label: 'Cotação BYX', value: 'R$ 0,95', change: '+2.5%', positive: true },
  { label: 'Lojistas Ativos', value: '1.2K+', change: '+10%', positive: true },
  { label: 'Transações/dia', value: '450+', change: '+8.2%', positive: true },
];

export default function MarketStats() {
  return (
    <GlassCard className="p-5">
      <h3 className="text-white font-semibold mb-4 flex items-center gap-2">
        <TrendingUp className="w-4 h-4 text-emerald-400" />
        Mercado BYX
      </h3>
      
      <div className="space-y-4">
        {stats.map((stat, index) => (
          <motion.div
            key={stat.label}
            initial={{ opacity: 0, x: 20 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.4, delay: index * 0.1 }}
            className="flex items-center justify-between"
          >
            <div>
              <p className="text-white font-semibold">{stat.value}</p>
              <p className="text-white/40 text-xs">{stat.label}</p>
            </div>
            <span className={`text-xs font-medium px-2 py-1 rounded-full ${stat.positive ? 'bg-emerald-400/20 text-emerald-400' : 'bg-rose-400/20 text-rose-400'}`}>
              {stat.change}
            </span>
          </motion.div>
        ))}
      </div>

      <div className="mt-6 pt-4 border-t border-white/10">
        <div className="flex items-center justify-between">
          <span className="text-white/40 text-sm">Taxa de Sucesso</span>
          <span className="text-emerald-400 font-bold text-2xl">98.5%</span>
        </div>
      </div>
    </GlassCard>
  );
}