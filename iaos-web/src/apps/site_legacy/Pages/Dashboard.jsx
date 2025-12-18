import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion } from 'framer-motion';
import { LayoutDashboard, TrendingUp, Activity, BarChart3, ChevronRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { createPageUrl } from '@/utils/siteLegacy';
import TabNavigation from '@/components/ui/TabNavigation';
import PromotionCarousel from '@/apps/site_legacy/Components/marketplace/PromotionCarousel';
import CategoriesGrid from '@/apps/site_legacy/Components/marketplace/CategoriesGrid';
import QuickActionsNav from '@/apps/site_legacy/Components/dashboard/QuickActionsNav';
import PartnersSection from '@/apps/site_legacy/Components/dashboard/PartnersSection';
import Footer from '@/apps/site_legacy/Components/layout/Footer';

export default function Dashboard() {
  const [activeTab, setActiveTab] = useState('overview');
  
  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => base44.auth.me(),
  });

  const tabs = [
    { id: 'overview', label: 'Visão Geral', icon: LayoutDashboard },
    { id: 'analytics', label: 'Análises', icon: BarChart3 },
    { id: 'performance', label: 'Performance', icon: TrendingUp },
    { id: 'activity', label: 'Atividade', icon: Activity },
  ];

  const { data: stores = [] } = useQuery({
    queryKey: ['stores'],
    queryFn: () => base44.entities.Store.list('-rating', 10),
  });

  const { data: products = [] } = useQuery({
    queryKey: ['featured-products'],
    queryFn: () => base44.entities.Product.filter({ status: 'available' }, '-created_date', 12),
  });

  return (
    <div className="min-h-screen bg-[#000000]">
      <div className="max-w-[1920px] mx-auto p-8 lg:p-12">
        {/* Quick Actions Navigation */}
        <QuickActionsNav />

        {/* Welcome & Partners Section */}
        <PartnersSection stores={stores} />

        {/* Tab Navigation */}
        <TabNavigation 
          tabs={tabs} 
          activeTab={activeTab} 
          onTabChange={setActiveTab} 
        />

        {/* Tab Content */}
        {activeTab === 'overview' && (
          <div className="space-y-8">
            {/* Promotion Carousel */}
            <PromotionCarousel />

            {/* Categories Grid */}
            <CategoriesGrid />

            {/* Featured Products Section - Últimos Anúncios */}
            <div>
              <div className="flex items-center justify-between mb-6">
                <div>
                  <h2 className="text-2xl font-bold text-white mb-1">Anúncios Recentes</h2>
                  <p className="text-white/50 text-sm">Produtos adicionados recentemente na plataforma</p>
                </div>
                <Link 
                  to={createPageUrl('Marketplace')}
                  className="text-emerald-400 hover:text-emerald-300 text-sm font-medium transition-colors flex items-center gap-1"
                >
                  Ver todos <ChevronRight className="w-4 h-4" />
                </Link>
              </div>
              
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
                {products.slice(0, 12).map((product, index) => (
                  <motion.div
                    key={product.id}
                    initial={{ opacity: 0, y: 20 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: index * 0.05 }}
                  >
                    <div className="bg-white/5 backdrop-blur-xl border border-white/10 rounded-2xl p-4 hover:border-emerald-500/30 hover:bg-white/10 transition-all cursor-pointer group">
                      <div className="aspect-square bg-black/20 rounded-xl mb-3 overflow-hidden">
                        {product.images?.[0] ? (
                          <img 
                            src={product.images[0]} 
                            alt={product.name}
                            className="w-full h-full object-cover group-hover:scale-110 transition-transform duration-300"
                          />
                        ) : (
                          <div className="w-full h-full flex items-center justify-center">
                            <span className="text-white/20 text-sm">Sem imagem</span>
                          </div>
                        )}
                      </div>
                      <h3 className="text-white font-medium mb-2 line-clamp-2 text-sm">{product.name}</h3>
                      <div className="flex items-center justify-between">
                        <span className="text-emerald-400 font-bold text-lg">{product.price_byx?.toLocaleString('pt-BR')} AIOS</span>
                        <span className="text-white/40 text-xs px-2 py-1 bg-white/5 rounded-lg">{product.condition}</span>
                      </div>
                    </div>
                  </motion.div>
                ))}
              </div>
            </div>
          </div>
        )}

        {activeTab === 'analytics' && (
          <div className="text-center py-16">
            <BarChart3 className="w-16 h-16 text-white/20 mx-auto mb-4" />
            <h3 className="text-white/60 text-lg mb-2">Análises em desenvolvimento</h3>
            <p className="text-white/40 text-sm">Em breve você terá acesso a análises detalhadas</p>
          </div>
        )}

        {activeTab === 'performance' && (
          <div className="text-center py-16">
            <TrendingUp className="w-16 h-16 text-white/20 mx-auto mb-4" />
            <h3 className="text-white/60 text-lg mb-2">Performance em desenvolvimento</h3>
            <p className="text-white/40 text-sm">Em breve você terá métricas de performance</p>
          </div>
        )}

        {activeTab === 'activity' && (
          <div className="text-center py-16">
            <Activity className="w-16 h-16 text-white/20 mx-auto mb-4" />
            <h3 className="text-white/60 text-lg mb-2">Atividade em desenvolvimento</h3>
            <p className="text-white/40 text-sm">Em breve você verá toda sua atividade aqui</p>
          </div>
        )}

        {/* Footer */}
        <Footer />
      </div>
    </div>
  );
}