import React from 'react';
import { Link } from 'react-router-dom';
import { createPageUrl } from '@/utils/siteLegacy';
import { motion } from 'framer-motion';
import { Store, ArrowRight } from 'lucide-react';

export default function PartnersSection({ stores }) {
  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 mb-8">
      {/* Welcome Section */}
      <motion.div
        initial={{ opacity: 0, x: -20 }}
        animate={{ opacity: 1, x: 0 }}
        className="flex flex-col justify-center"
      >
        <h1 className="text-4xl md:text-5xl font-bold text-white mb-4">
          Bem-vindo ao <span className="text-transparent bg-clip-text bg-gradient-to-r from-emerald-400 to-cyan-400">AIOS</span>
        </h1>
        <p className="text-white/60 text-lg">
          Sua plataforma de comércio digital com cashback e pagamentos em AIOS
        </p>
      </motion.div>

      {/* Partner Stores */}
      <motion.div
        initial={{ opacity: 0, x: 20 }}
        animate={{ opacity: 1, x: 0 }}
        className="relative"
      >
        <Link to={createPageUrl('Stores')}>
          <div className="relative group cursor-pointer">
            <div className="absolute -inset-1 bg-gradient-to-r from-emerald-500 to-cyan-500 rounded-3xl blur-xl opacity-30 group-hover:opacity-50 transition-opacity" />
            
            <div className="relative bg-white/5 backdrop-blur-xl border border-white/10 rounded-3xl p-6 overflow-hidden group-hover:border-white/20 transition-all">
              <div className="flex items-center justify-between mb-6">
                <div>
                  <h3 className="text-xl font-bold text-white mb-1">Conheça nossos Lojistas Parceiros</h3>
                  <p className="text-white/50 text-sm">Descubra lojas e produtos incríveis</p>
                </div>
                <ArrowRight className="w-6 h-6 text-emerald-400 group-hover:translate-x-2 transition-transform" />
              </div>

              <div className="flex gap-4 overflow-hidden">
                {stores.slice(0, 4).map((store, index) => (
                  <motion.div
                    key={store.id}
                    initial={{ opacity: 0, scale: 0.8 }}
                    animate={{ opacity: 1, scale: 1 }}
                    transition={{ delay: index * 0.1 }}
                    whileHover={{ scale: 1.1, zIndex: 10 }}
                    className="relative"
                  >
                    <div className="w-20 h-20 rounded-full bg-gradient-to-br from-emerald-400 to-cyan-400 flex items-center justify-center text-white text-2xl font-bold border-4 border-black/20 shadow-xl group-hover:shadow-2xl transition-all">
                      {store.logo_url ? (
                        <img 
                          src={store.logo_url} 
                          alt={store.name} 
                          className="w-full h-full object-cover rounded-full"
                        />
                      ) : (
                        store.name?.charAt(0).toUpperCase()
                      )}
                    </div>
                  </motion.div>
                ))}
              </div>

              {stores.length > 4 && (
                <div className="mt-4 text-emerald-400 text-sm font-medium flex items-center gap-1">
                  <Store className="w-4 h-4" />
                  +{stores.length - 4} lojas parceiras
                </div>
              )}
            </div>
          </div>
        </Link>
      </motion.div>
    </div>
  );
}