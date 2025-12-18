import React from 'react';
import { Link } from 'react-router-dom';
import { createPageUrl } from '@/utils/siteLegacy';
import { motion } from 'framer-motion';
import { 
  Store, 
  MessageCircle, 
  TrendingUp, 
  DollarSign,
  QrCode,
  BarChart3
} from 'lucide-react';

const actions = [
  { 
    name: 'Minha Loja', 
    page: 'MyStore', 
    icon: Store, 
    color: 'from-emerald-500 to-cyan-500' 
  },
  { 
    name: 'Chat', 
    page: 'Chat', 
    icon: MessageCircle, 
    color: 'from-blue-500 to-purple-500' 
  },
  { 
    name: 'Vendas', 
    page: 'SalesCashback', 
    icon: TrendingUp, 
    color: 'from-purple-500 to-pink-500' 
  },
  { 
    name: 'Trade', 
    page: 'Trade', 
    icon: DollarSign, 
    color: 'from-amber-500 to-orange-500' 
  },
  { 
    name: 'QR Payments', 
    page: 'MyStore', 
    icon: QrCode, 
    color: 'from-pink-500 to-rose-500' 
  },
  { 
    name: 'Análises', 
    page: 'Dashboard', 
    icon: BarChart3, 
    color: 'from-green-500 to-teal-500' 
  },
];

export default function QuickActionsNav() {
  return (
    <div className="mb-8">
      <div className="grid grid-cols-3 md:grid-cols-6 gap-3">
        {actions.map((action, index) => (
          <Link key={action.name} to={createPageUrl(action.page)}>
            <motion.div
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: index * 0.05 }}
              className="group cursor-pointer"
            >
              <div className={`
                relative overflow-hidden rounded-2xl p-4 
                bg-white/5 backdrop-blur-xl border border-white/10 
                hover:border-white/20 transition-all duration-300
                hover:scale-105 hover:shadow-xl
              `}>
                <div className={`
                  w-12 h-12 rounded-xl mx-auto mb-2
                  bg-gradient-to-br ${action.color} 
                  flex items-center justify-center
                  group-hover:scale-110 transition-transform duration-300
                `}>
                  <action.icon className="w-6 h-6 text-white" />
                </div>
                <p className="text-white text-sm font-medium text-center">
                  {action.name}
                </p>
              </div>
            </motion.div>
          </Link>
        ))}
      </div>
    </div>
  );
}