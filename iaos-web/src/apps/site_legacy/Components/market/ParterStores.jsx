import React from 'react';
import { motion } from 'framer-motion';
import { Button } from "@/components/ui/button";
import { ChevronRight, Star, BadgeCheck } from 'lucide-react';
import GlassCard from '../ui/GlassCard';
import { Link } from 'react-router-dom';
import { createPageUrl } from '@/utils/siteLegacy';

export default function PartnerStores({ stores = [] }) {
  const displayStores = stores.slice(0, 4);

  return (
    <GlassCard className="p-5">
      <h3 className="text-white font-semibold mb-4">Lojistas Parceiros</h3>
      
      <div className="space-y-3">
        {displayStores.length === 0 ? (
          <div className="text-center py-4 text-white/40 text-sm">
            Nenhuma loja cadastrada
          </div>
        ) : (
          displayStores.map((store, index) => (
            <motion.div
              key={store.id}
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ duration: 0.3, delay: index * 0.1 }}
              className="flex items-center justify-between p-3 rounded-xl bg-white/5 hover:bg-white/10 transition-colors"
            >
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-slate-700 to-slate-800 flex items-center justify-center text-white font-bold">
                  {store.name?.charAt(0).toUpperCase()}
                </div>
                <div>
                  <div className="flex items-center gap-1">
                    <p className="text-white font-medium text-sm">{store.name}</p>
                    {store.is_verified && <BadgeCheck className="w-3 h-3 text-emerald-400" />}
                  </div>
                  <div className="flex items-center gap-1 text-white/40 text-xs">
                    <Star className="w-3 h-3 fill-amber-400 text-amber-400" />
                    <span>{store.rating?.toFixed(1) || '5.0'}</span>
                  </div>
                </div>
              </div>
              <Button 
                variant="ghost" 
                size="sm" 
                className="text-emerald-400 hover:text-emerald-300 hover:bg-emerald-400/10 text-xs"
              >
                Visitar
              </Button>
            </motion.div>
          ))
        )}
      </div>

      <Link to={createPageUrl('Stores')}>
        <Button 
          variant="outline" 
          className="w-full mt-4 border-white/10 text-white/70 hover:text-white hover:bg-white/5"
        >
          Ver todos os lojistas <ChevronRight className="w-4 h-4 ml-1" />
        </Button>
      </Link>
    </GlassCard>
  );
}