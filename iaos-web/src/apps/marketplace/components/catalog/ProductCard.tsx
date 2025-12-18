import { useNavigate } from 'react-router-dom';
import { Heart, Star, Package, AlertTriangle, XCircle, Eye, ShoppingCart, TrendingUp } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card } from '@/components/ui/card';
import { Product } from '@/apps/marketplace/types/product';

interface ProductCardProps {
  product: Product;
}

export const ProductCard = ({ product }: ProductCardProps) => {
  const navigate = useNavigate();

  const discount = product.originalPrice
    ? Math.round(((product.originalPrice - product.price) / product.originalPrice) * 100)
    : 0;

  const getStockBadge = () => {
    switch (product.stockStatus) {
      case 'available':
        return (
          <Badge className="badge-stock-available text-xs">
            <Package className="h-3 w-3 mr-1" />
            {product.stock} disponíveis
          </Badge>
        );
      case 'low':
        return (
          <Badge className="badge-stock-low text-xs">
            <AlertTriangle className="h-3 w-3 mr-1" />
            Últimas {product.stock} unid.
          </Badge>
        );
      case 'out':
        return (
          <Badge className="badge-stock-out text-xs">
            <XCircle className="h-3 w-3 mr-1" />
            Esgotado
          </Badge>
        );
    }
  };

  return (
    <Card
      className="group bg-card border border-border rounded-xl overflow-hidden cursor-pointer product-card-hover shadow-card"
      onClick={() => navigate(`/marketplace/product/${product.id}`)}
    >
      {/* Image Section */}
      <div className="relative aspect-[4/3] bg-muted overflow-hidden">
        <img
          src={product.image}
          alt={product.name}
          className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
        />
        
        {/* Badges */}
        <div className="absolute top-3 left-3 flex flex-col gap-2">
          {discount > 0 && (
            <Badge className="bg-destructive text-destructive-foreground font-semibold">
              -{discount}%
            </Badge>
          )}
          {product.inCarts > 5 && (
            <Badge className="badge-popularity">
              <TrendingUp className="h-3 w-3 mr-1" />
              Em {product.inCarts} carrinhos
            </Badge>
          )}
        </div>

        {/* Wishlist Button */}
        <Button
          variant="ghost"
          size="icon"
          className="absolute top-3 right-3 bg-card/80 hover:bg-card shadow-md"
          onClick={(e) => {
            e.stopPropagation();
            // Handle wishlist
          }}
        >
          <Heart className="h-4 w-4" />
        </Button>

        {/* Quick view count */}
        <div className="absolute bottom-3 right-3 bg-card/90 backdrop-blur-sm rounded-full px-2 py-1 flex items-center gap-1 text-xs text-muted-foreground">
          <Eye className="h-3 w-3" />
          {product.viewCount}
        </div>
      </div>

      {/* Content Section */}
      <div className="p-4 space-y-3">
        {/* Partner & Rating */}
        <div className="flex items-center justify-between">
          <span className="text-xs text-primary font-medium hover:underline">
            {product.partner}
          </span>
          <div className="flex items-center gap-1">
            <Star className="h-3 w-3 fill-warning text-warning" />
            <span className="text-xs text-muted-foreground">{product.partnerRating}</span>
          </div>
        </div>

        {/* Title */}
        <h3 className="font-medium text-foreground line-clamp-2 text-sm leading-snug group-hover:text-primary transition-colors">
          {product.name}
        </h3>

        {/* Stock */}
        <div>{getStockBadge()}</div>

        {/* Price */}
        <div className="space-y-1">
          {product.originalPrice && (
            <span className="text-sm text-muted-foreground line-through">
              R$ {product.originalPrice.toLocaleString('pt-BR')}
            </span>
          )}
          <div className="flex items-baseline gap-1">
            <span className="text-xl font-bold text-foreground">
              R$ {product.price.toLocaleString('pt-BR')}
            </span>
          </div>
          {product.minOrder > 1 && (
            <span className="text-xs text-muted-foreground">
              Pedido mínimo: {product.minOrder} unid.
            </span>
          )}
        </div>

        {/* CTA */}
        <Button
          className="w-full btn-secondary-cta py-2 text-sm"
        onClick={(e) => {
          e.stopPropagation();
            navigate(`/marketplace/product/${product.id}`);
        }}
      >
          <ShoppingCart className="h-4 w-4 mr-2" />
          Ver Detalhes
        </Button>
      </div>
    </Card>
  );
};
