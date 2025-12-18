import React from 'react';
import { motion } from 'framer-motion';
import { Send, QrCode, ArrowLeftRight, Calculator } from 'lucide-react';
import GlassCard from '../ui/GlassCard';

const actions = [
  { icon: Send, label: 'Enviar BYX', description: 'Transferir para outra carteira', color: 'from-rose-500 to-pink-500' },
  { icon: QrCode, label: 'Receber BYX', description: 'Gerar QR Code ou link', color: 'from-emerald-500 to-cyan-500' },
  { icon: ArrowLeftRight, label: 'Converter', description: 'BRL ↔ BYX', color: 'from-blue-500 to-indigo-500' },
  { icon: Calculator, label: 'Calculadora', description: 'Simular conversão', color: 'from-purple-500 to-violet-500' },
];

export default function QuickActions({ onAction }) {
  return (
    <div>
      <h3 className="text-white/70 font-medium mb-4">Ações Rápidas</h3>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {actions.map((action, index) => (
          <motion.div
            key={action.label}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.4, delay: index * 0.1 }}
          >
            <GlassCard 
              className="p-4 cursor-pointer group hover:bg-white/10 transition-all duration-300"
              onClick={() => onAction?.(action.label)}
            >
              <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${action.color} flex items-center justify-center mb-3 group-hover:scale-110 transition-transform duration-300`}>
                <action.icon className="w-5 h-5 text-white" />
              </div>
              <h4 className="text-white font-medium text-sm mb-1">{action.label}</h4>
              <p className="text-white/40 text-xs">{action.description}</p>
            </GlassCard>
          </motion.div>
        ))}
      </div>
    </div>
  );
}