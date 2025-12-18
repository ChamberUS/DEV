import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion } from 'framer-motion';
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { Package, ChevronLeft, ChevronRight, Smartphone, Laptop, Tablet, Gamepad2, Headphones, Watch, Camera } from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';
import { Separator } from "@/components/ui/separator";

const brands = [
  'Dell', 'Lenovo', 'Apple', 'Positivo', 'Multilaser', 'HP', 'Samsung', 
  'Asus', 'Acer', 'LG', 'Motorola', 'Xiaomi', 'Sony'
];

const priceRanges = [
  { id: '0-1000', label: 'Até R$ 1.000', min: 0, max: 1000 },
  { id: '1000-2000', label: 'R$ 1.000 - R$ 2.000', min: 1000, max: 2000 },
  { id: '2000-5000', label: 'R$ 2.000 - R$ 5.000', min: 2000, max: 5000 },
  { id: '5000+', label: 'Acima de R$ 5.000', min: 5000, max: 999999 },
];

const categories = [
  { id: 'celulares', label: 'Celulares', icon: Smartphone },
  { id: 'notebooks', label: 'Notebooks', icon: Laptop },
  { id: 'tablets', label: 'Tablets', icon: Tablet },
  { id: 'games', label: 'Games', icon: Gamepad2 },
  { id: 'audio', label: 'Áudio', icon: Headphones },
  { id: 'smartwatch', label: 'Smartwatch', icon: Watch },
  { id: 'cameras', label: 'Câmeras', icon: Camera },
];

const ITEMS_PER_PAGE = 10;

export default function Marketplace() {
  const [currentPage, setCurrentPage] = useState(1);
  const [selectedBrands, setSelectedBrands] = useState([]);
  const [selectedPriceRange, setSelectedPriceRange] = useState(null);
  const [selectedCategory, setSelectedCategory] = useState(null);

  const { data: allProducts = [], isLoading } = useQuery({
    queryKey: ['products'],
    queryFn: () => base44.entities.Product.filter({ status: 'available' }, '-created_date'),
  });

  // Produtos de última hora (últimos 6)
  const latestProducts = allProducts.slice(0, 6);

  // Filtrar produtos
  const filteredProducts = allProducts.filter(product => {
    // Filtro por categoria
    if (selectedCategory && product.category !== selectedCategory) return false;

    // Filtro por marca
    if (selectedBrands.length > 0) {
      const productBrand = product.name?.toLowerCase() || '';
      const matchesBrand = selectedBrands.some(brand => 
        productBrand.includes(brand.toLowerCase())
      );
      if (!matchesBrand) return false;
    }

    // Filtro por preço
    if (selectedPriceRange) {
      const priceBRL = product.price_brl || (product.price_byx * 0.95);
      if (priceBRL < selectedPriceRange.min || priceBRL > selectedPriceRange.max) {
        return false;
      }
    }

    return true;
  });

  // Paginação
  const totalPages = Math.ceil(filteredProducts.length / ITEMS_PER_PAGE);
  const startIndex = (currentPage - 1) * ITEMS_PER_PAGE;
  const paginatedProducts = filteredProducts.slice(startIndex, startIndex + ITEMS_PER_PAGE);

  const toggleBrand = (brand) => {
    setSelectedBrands(prev => 
      prev.includes(brand) 
        ? prev.filter(b => b !== brand)
        : [...prev, brand]
    );
    setCurrentPage(1);
  };

  return (
    <div className="p-6 lg:p-8">
      {/* Header */}
      <motion.div 
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="mb-8"
      >
        <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Marketplace</h1>
        <p className="text-white/50">Encontre os melhores eletrônicos com pagamento em AIOS</p>
      </motion.div>

      {/* Produtos de Última Hora - Carrossel */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1 }}
        className="mb-8"
      >
        <GlassCard className="p-6">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-xl font-bold text-white">🔥 Última Hora</h2>
            <div className="flex gap-2">
              {categories.map(cat => (
                <button
                  key={cat.id}
                  onClick={() => setSelectedCategory(cat.id)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                    selectedCategory === cat.id
                      ? 'bg-gradient-to-r from-emerald-500 to-cyan-500 text-white'
                      : 'bg-white/5 text-white/60 hover:bg-white/10'
                  }`}
                >
                  {cat.label}
                </button>
              ))}
            </div>
          </div>
          
          <div className="flex gap-4 overflow-x-auto pb-2">
            {latestProducts.map((product, index) => (
              <motion.div
                key={product.id}
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: index * 0.1 }}
                className="flex-shrink-0 w-48 bg-white/5 rounded-xl p-3 border border-white/10 hover:border-emerald-500/30 cursor-pointer group"
              >
                <div className="aspect-square bg-black/20 rounded-lg mb-2 overflow-hidden">
                  {product.images?.[0] ? (
                    <img 
                      src={product.images[0]} 
                      alt={product.name}
                      className="w-full h-full object-cover group-hover:scale-110 transition-transform"
                    />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center">
                      <Package className="w-8 h-8 text-white/20" />
                    </div>
                  )}
                </div>
                <h4 className="text-white text-sm font-medium line-clamp-2 mb-1">{product.name}</h4>
                <p className="text-emerald-400 font-bold text-sm">{product.price_byx?.toLocaleString('pt-BR')} AIOS</p>
              </motion.div>
            ))}
          </div>
        </GlassCard>
      </motion.div>

      {/* Main Content - Sidebar + Products */}
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
        {/* Sidebar */}
        <motion.div
          initial={{ opacity: 0, x: -20 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ delay: 0.2 }}
          className="lg:col-span-1"
        >
          <GlassCard className="p-6 sticky top-20">
            <h3 className="text-lg font-bold text-white mb-4">Filtros</h3>

            {/* Preço */}
            <div className="mb-6">
              <Label className="text-white/70 text-sm font-semibold mb-3 block">Preço</Label>
              <div className="space-y-2">
                {priceRanges.map(range => (
                  <div key={range.id} className="flex items-center">
                    <Checkbox 
                      id={range.id}
                      checked={selectedPriceRange?.id === range.id}
                      onCheckedChange={(checked) => {
                        setSelectedPriceRange(checked ? range : null);
                        setCurrentPage(1);
                      }}
                      className="border-white/20"
                    />
                    <label htmlFor={range.id} className="ml-2 text-white/60 text-sm cursor-pointer hover:text-white">
                      {range.label}
                    </label>
                  </div>
                ))}
              </div>
            </div>

            <Separator className="bg-white/10 mb-6" />

            {/* Marcas */}
            <div>
              <Label className="text-white/70 text-sm font-semibold mb-3 block">Marca</Label>
              <div className="space-y-2 max-h-64 overflow-y-auto">
                {brands.map(brand => (
                  <div key={brand} className="flex items-center">
                    <Checkbox 
                      id={brand}
                      checked={selectedBrands.includes(brand)}
                      onCheckedChange={() => toggleBrand(brand)}
                      className="border-white/20"
                    />
                    <label htmlFor={brand} className="ml-2 text-white/60 text-sm cursor-pointer hover:text-white">
                      {brand}
                    </label>
                  </div>
                ))}
              </div>
            </div>

            {/* Clear Filters */}
            {(selectedBrands.length > 0 || selectedPriceRange || selectedCategory) && (
              <Button
                onClick={() => {
                  setSelectedBrands([]);
                  setSelectedPriceRange(null);
                  setSelectedCategory(null);
                  setCurrentPage(1);
                }}
                variant="outline"
                className="w-full mt-4 border-white/20 text-white hover:bg-white/10"
              >
                Limpar Filtros
              </Button>
            )}
          </GlassCard>
        </motion.div>

        {/* Products List */}
        <div className="lg:col-span-3">
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.3 }}
          >
            <div className="mb-4 text-white/50 text-sm">
              {filteredProducts.length} produtos encontrados
            </div>

            {isLoading ? (
              <div className="space-y-4">
                {Array(5).fill(0).map((_, i) => (
                  <GlassCard key={i} className="h-48 animate-pulse" />
                ))}
              </div>
            ) : paginatedProducts.length === 0 ? (
              <GlassCard className="p-12 text-center">
                <Package className="w-16 h-16 text-white/20 mx-auto mb-4" />
                <h3 className="text-white/60 text-lg mb-2">Nenhum produto encontrado</h3>
                <p className="text-white/40 text-sm">Tente ajustar os filtros</p>
              </GlassCard>
            ) : (
              <>
                <div className="space-y-4 mb-6">
                  {paginatedProducts.map((product, index) => (
                    <motion.div
                      key={product.id}
                      initial={{ opacity: 0, y: 20 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ delay: index * 0.05 }}
                    >
                      <GlassCard className="p-4 hover:bg-white/10 transition-all cursor-pointer">
                        <div className="flex gap-4">
                          {/* Product Image */}
                          <div className="flex-shrink-0 w-32 h-32 bg-black/20 rounded-xl overflow-hidden">
                            {product.images?.[0] ? (
                              <img 
                                src={product.images[0]} 
                                alt={product.name}
                                className="w-full h-full object-cover"
                              />
                            ) : (
                              <div className="w-full h-full flex items-center justify-center">
                                <Package className="w-12 h-12 text-white/20" />
                              </div>
                            )}
                          </div>

                          {/* Product Info */}
                          <div className="flex-1 flex flex-col justify-between">
                            <div>
                              <h3 className="text-white font-semibold text-lg mb-2">{product.name}</h3>
                              <p className="text-white/50 text-sm line-clamp-2">{product.description}</p>
                            </div>
                            <div className="flex items-center gap-2">
                              <Badge variant="outline" className="border-emerald-500/30 text-emerald-400">
                                {product.condition}
                              </Badge>
                              <Badge variant="outline" className="border-white/20 text-white/60">
                                {product.category}
                              </Badge>
                            </div>
                          </div>

                          {/* Price */}
                          <div className="flex-shrink-0 flex flex-col items-end justify-between">
                            <div className="text-right">
                              <p className="text-emerald-400 font-bold text-2xl">{product.price_byx?.toLocaleString('pt-BR')} AIOS</p>
                              <p className="text-white/40 text-sm">≈ R$ {(product.price_brl || product.price_byx * 0.95).toLocaleString('pt-BR', { minimumFractionDigits: 2 })}</p>
                            </div>
                            <Button className="bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600">
                              Ver detalhes
                            </Button>
                          </div>
                        </div>
                      </GlassCard>
                    </motion.div>
                  ))}
                </div>

                {/* Pagination */}
                {totalPages > 1 && (
                  <div className="flex items-center justify-center gap-2">
                    <Button
                      variant="outline"
                      size="icon"
                      onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                      disabled={currentPage === 1}
                      className="border-white/20 text-white hover:bg-white/10"
                    >
                      <ChevronLeft className="w-4 h-4" />
                    </Button>
                    
                    <div className="flex gap-2">
                      {Array.from({ length: totalPages }, (_, i) => i + 1).map(page => (
                        <Button
                          key={page}
                          variant={currentPage === page ? "default" : "outline"}
                          size="sm"
                          onClick={() => setCurrentPage(page)}
                          className={
                            currentPage === page
                              ? "bg-gradient-to-r from-emerald-500 to-cyan-500"
                              : "border-white/20 text-white hover:bg-white/10"
                          }
                        >
                          {page}
                        </Button>
                      ))}
                    </div>

                    <Button
                      variant="outline"
                      size="icon"
                      onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                      disabled={currentPage === totalPages}
                      className="border-white/20 text-white hover:bg-white/10"
                    >
                      <ChevronRight className="w-4 h-4" />
                    </Button>
                  </div>
                )}
              </>
            )}
          </motion.div>
        </div>
      </div>
    </div>
  );
}