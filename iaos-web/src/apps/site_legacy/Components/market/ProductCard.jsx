import React from 'react';
import { motion } from 'framer-motion';
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Heart, Eye, ShoppingCart, Smartphone, Laptop, Gamepad2, Headphones, Watch, Camera, Tablet, Package, Star } from 'lucide-react';
import GlassCard from '../ui/GlassCard';
import { useNavigate } from 'react-router-dom';
import { createPageUrl } from '@/utils/siteLegacy';

const categoryIcons = {
  celulares: Smartphone,
  notebooks: Laptop,
  tablets: Tablet,
  games: Gamepad2,
  acessorios: Package,
  audio: Headphones,
  cameras: Camera,
  smartwatch: Watch,
  outros: Package,
};

const conditionColors = {
  novo: 'bg-emerald-400/20 text-emerald-400 border-emerald-400/30',
  seminovo: 'bg-blue-400/20 text-blue-400 border-blue-400/30',
  usado: 'bg-amber-400/20 text-amber-400 border-amber-400/30',
};

export default function ProductCard({ product, index = 0, reviews = [] }) {
  const navigate = useNavigate();
  const CategoryIcon = categoryIcons[product.category] || Package;
  const imageUrl = product.images?.[0] || `https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?w=400&h=300&fit=crop`;
  
  const averageRating = reviews.length > 0
    ? reviews.reduce((acc, r) => acc + r.rating, 0) / reviews.length
    : 0;

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, delay: index * 0.1 }}
    >
      <GlassCard 
        className="overflow-hidden group cursor-pointer"
        onClick={() => navigate(createPageUrl('ProductDetail') + `?id=${product.id}`)}
      >
        <div className="relative aspect-[4/3] overflow-hidden">
          <img 
            src={imageUrl}
            alt={product.name}
            className="w-full h-full object-cover transition-transform duration-500 group-hover:scale-110"
          />
          <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-transparent" />
          
          {/* Badges */}
          <div className="absolute top-3 left-3 flex gap-2">
            <Badge className={`${conditionColors[product.condition]} border text-xs`}>
              {product.condition}
            </Badge>
          </div>
          
          {/* Actions */}
          <div className="absolute top-3 right-3 flex flex-col gap-2 opacity-0 group-hover:opacity-100 transition-opacity">
            <button className="w-8 h-8 rounded-full bg-white/10 backdrop-blur-sm flex items-center justify-center hover:bg-white/20 transition-colors">
              <Heart className="w-4 h-4 text-white" />
            </button>
          </div>

          {/* Category Icon */}
          <div className="absolute bottom-3 right-3">
            <div className="w-8 h-8 rounded-lg bg-white/10 backdrop-blur-sm flex items-center justify-center">
              <CategoryIcon className="w-4 h-4 text-white" />
            </div>
          </div>
        </div>

        <div className="p-4">
          <h4 className="text-white font-semibold mb-1 truncate">{product.name}</h4>
          {averageRating > 0 && (
            <div className="flex items-center gap-1 mb-2">
              <Star className="w-3 h-3 fill-amber-400 text-amber-400" />
              <span className="text-white/60 text-xs">{averageRating.toFixed(1)}</span>
              <span className="text-white/30 text-xs">({reviews.length})</span>
            </div>
          )}
          <p className="text-white/40 text-xs mb-3 line-clamp-2">{product.description}</p>
          
          <div className="flex items-center justify-between mb-4">
            <div>
              <p className="text-emerald-400 font-bold text-lg">{product.price_byx?.toLocaleString('pt-BR')} BYX</p>
              <p className="text-white/30 text-xs">≈ R$ {product.price_brl?.toLocaleString('pt-BR', { minimumFractionDigits: 2 })}</p>
            </div>
            <div className="flex items-center gap-1 text-white/40 text-xs">
              <Eye className="w-3 h-3" />
              <span>{product.views || 0}</span>
            </div>
          </div>

          <Button 
            className="w-full bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white border-0"
            onClick={(e) => {
              e.stopPropagation();
              navigate(createPageUrl('ProductDetail') + `?id=${product.id}`);
            }}
          >
            <ShoppingCart className="w-4 h-4 mr-2" />
            Ver detalhes
          </Button>
        </div>
      </GlassCard>
    </motion.div>
  );
}