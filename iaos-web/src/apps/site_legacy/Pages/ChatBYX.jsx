import React from 'react';
import { motion } from 'framer-motion';
import { MessageCircle } from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';

export default function ChatBYX() {
  return (
    <div className="p-6 lg:p-8">
      <motion.div 
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="mb-8"
      >
        <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Chat with BYX</h1>
        <p className="text-white/50">Converse com o assistente BYX</p>
      </motion.div>

      <GlassCard className="p-12">
        <div className="text-center py-16">
          <MessageCircle className="w-16 h-16 text-white/20 mx-auto mb-4" />
          <h3 className="text-white/60 text-lg mb-2">Chat with BYX em desenvolvimento</h3>
          <p className="text-white/40 text-sm">Em breve você poderá conversar com nosso assistente virtual</p>
        </div>
      </GlassCard>
    </div>
  );
}