import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion } from 'framer-motion';
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Search, Star, BadgeCheck, MapPin, ShoppingBag, Users, Store as StoreIcon, Plus, Phone, Mail } from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';
import { Link } from 'react-router-dom';
import { createPageUrl } from '@/utils/siteLegacy';

const categoryLabels = {
  eletronicos: 'Eletrônicos',
  celulares: 'Celulares',
  computadores: 'Computadores',
  games: 'Games',
  acessorios: 'Acessórios',
  outros: 'Outros',
};

export default function Stores() {
  const [searchTerm, setSearchTerm] = useState('');

  const { data: stores = [], isLoading } = useQuery({
    queryKey: ['stores'],
    queryFn: () => base44.entities.Store.list('-rating'),
  });

  const { data: products = [] } = useQuery({
    queryKey: ['allProducts'],
    queryFn: () => base44.entities.Product.list(),
  });

  const filteredStores = stores.filter(store =>
    store.name?.toLowerCase().includes(searchTerm.toLowerCase()) ||
    store.description?.toLowerCase().includes(searchTerm.toLowerCase())
  );

  return (
    <div className="p-6 lg:p-8">
      {/* Header */}
      <motion.div 
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8"
      >
        <div>
          <h1 className="text-3xl md:text-4xl font-bold text-white mb-2">Lojistas Parceiros</h1>
          <p className="text-white/50">Conheça nossos parceiros verificados e seus produtos</p>
        </div>
        <Link to={createPageUrl('MyStore')}>
          <Button className="bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white border-0">
            <Plus className="w-4 h-4 mr-2" />
            Criar minha loja
          </Button>
        </Link>
      </motion.div>

      {/* Search */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1 }}
      >
        <GlassCard className="p-4 mb-8">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-white/40" />
            <Input
              placeholder="Buscar lojas por nome, categoria ou localização..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="pl-10 bg-white/5 border-white/10 text-white placeholder:text-white/40 focus:border-emerald-500/50"
            />
          </div>
        </GlassCard>
      </motion.div>

      {/* Stats */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.2 }}
        className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8"
      >
        <GlassCard className="p-4 text-center">
          <StoreIcon className="w-8 h-8 text-emerald-400 mx-auto mb-2" />
          <p className="text-2xl font-bold text-white">{stores.length}</p>
          <p className="text-white/40 text-sm">Lojas Ativas</p>
        </GlassCard>
        <GlassCard className="p-4 text-center">
          <BadgeCheck className="w-8 h-8 text-blue-400 mx-auto mb-2" />
          <p className="text-2xl font-bold text-white">{stores.filter(s => s.is_verified).length}</p>
          <p className="text-white/40 text-sm">Verificadas</p>
        </GlassCard>
        <GlassCard className="p-4 text-center">
          <ShoppingBag className="w-8 h-8 text-purple-400 mx-auto mb-2" />
          <p className="text-2xl font-bold text-white">{products.length}</p>
          <p className="text-white/40 text-sm">Produtos Disponíveis</p>
        </GlassCard>
        <GlassCard className="p-4 text-center">
          <Star className="w-8 h-8 text-amber-400 mx-auto mb-2" />
          <p className="text-2xl font-bold text-white">
            {stores.length > 0 ? (stores.reduce((acc, s) => acc + (s.rating || 5), 0) / stores.length).toFixed(1) : '5.0'}
          </p>
          <p className="text-white/40 text-sm">Avaliação Média</p>
        </GlassCard>
      </motion.div>

      {/* Stores List */}
      <div className="space-y-6">
        {isLoading ? (
          Array(4).fill(0).map((_, i) => (
            <GlassCard key={i} className="h-48 animate-pulse" />
          ))
        ) : filteredStores.length === 0 ? (
          <GlassCard className="p-12 text-center">
            <StoreIcon className="w-16 h-16 text-white/20 mx-auto mb-4" />
            <h3 className="text-white/60 text-lg mb-2">Nenhuma loja encontrada</h3>
            <p className="text-white/40 text-sm">Seja o primeiro a criar uma loja!</p>
          </GlassCard>
        ) : (
          filteredStores.map((store, index) => {
            const storeProducts = products.filter(p => p.store_id === store.id);
            
            return (
              <motion.div
                key={store.id}
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.4, delay: index * 0.1 }}
              >
                <GlassCard className="p-6 hover:bg-white/10 transition-all">
                  <div className="flex flex-col lg:flex-row gap-6">
                    {/* Store Info */}
                    <div className="flex items-start gap-4 flex-1">
                      {/* Logo */}
                      <div className="flex-shrink-0">
                        <div className="w-24 h-24 rounded-2xl bg-gradient-to-br from-emerald-400 to-cyan-400 flex items-center justify-center text-3xl font-bold text-white shadow-lg">
                          {store.logo_url ? (
                            <img src={store.logo_url} alt={store.name} className="w-full h-full object-cover rounded-2xl" />
                          ) : (
                            store.name?.charAt(0).toUpperCase()
                          )}
                        </div>
                      </div>

                      {/* Details */}
                      <div className="flex-1">
                        <div className="flex items-center gap-2 mb-2">
                          <h3 className="text-white font-bold text-2xl">{store.name}</h3>
                          {store.is_verified && (
                            <BadgeCheck className="w-6 h-6 text-emerald-400" />
                          )}
                        </div>

                        <div className="flex items-center gap-2 mb-3">
                          <Badge className="bg-emerald-500/20 text-emerald-400 border-0">
                            {categoryLabels[store.category] || store.category}
                          </Badge>
                          <div className="flex items-center gap-1 text-amber-400">
                            <Star className="w-4 h-4 fill-amber-400" />
                            <span className="font-semibold">{store.rating?.toFixed(1) || '5.0'}</span>
                          </div>
                        </div>

                        <p className="text-white/60 mb-4 leading-relaxed">
                          {store.description || 'Loja especializada em eletrônicos de qualidade'}
                        </p>

                        {/* Additional Info */}
                        <div className="grid grid-cols-2 gap-3 text-sm">
                          {store.actual_owner_email && (
                            <div className="flex items-center gap-2 text-white/50">
                              <Mail className="w-4 h-4" />
                              <span className="truncate">{store.actual_owner_email}</span>
                            </div>
                          )}
                          {store.actual_owner_phone && (
                            <div className="flex items-center gap-2 text-white/50">
                              <Phone className="w-4 h-4" />
                              <span>{store.actual_owner_phone}</span>
                            </div>
                          )}
                          <div className="flex items-center gap-2 text-white/50">
                            <ShoppingBag className="w-4 h-4" />
                            <span>{store.total_sales || 0} vendas</span>
                          </div>
                          <div className="flex items-center gap-2 text-white/50">
                            <Users className="w-4 h-4" />
                            <span>{store.employee_count || '1-5'} funcionários</span>
                          </div>
                        </div>

                        {/* Monthly Revenue Badge */}
                        {store.monthly_revenue && (
                          <div className="mt-3">
                            <Badge variant="outline" className="border-white/20 text-white/60">
                              Faturamento: R$ {store.monthly_revenue.replace('-', ' - ')}
                            </Badge>
                          </div>
                        )}
                      </div>
                    </div>

                    {/* Store Products Preview */}
                    <div className="lg:w-80">
                      <h4 className="text-white font-semibold mb-3 text-sm">Produtos em Destaque</h4>
                      <div className="grid grid-cols-3 gap-2">
                        {storeProducts.slice(0, 3).map(product => (
                          <div key={product.id} className="aspect-square bg-black/20 rounded-lg overflow-hidden">
                            {product.images?.[0] ? (
                              <img 
                                src={product.images[0]} 
                                alt={product.name}
                                className="w-full h-full object-cover"
                              />
                            ) : (
                              <div className="w-full h-full flex items-center justify-center">
                                <ShoppingBag className="w-6 h-6 text-white/20" />
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                      <Button className="w-full mt-3 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600">
                        Ver {storeProducts.length} Produtos
                      </Button>
                    </div>
                  </div>
                </GlassCard>
              </motion.div>
            );
          })
        )}
      </div>
    </div>
  );
}