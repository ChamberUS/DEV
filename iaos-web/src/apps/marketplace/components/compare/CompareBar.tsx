import { X, BarChart3 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useCompare } from '@/apps/marketplace/hooks/useCompare';
import { useNavigate } from 'react-router-dom';

export const CompareBar = () => {
  const { products, removeProduct, clearAll } = useCompare();
  const navigate = useNavigate();

  if (products.length === 0) return null;

  return (
    <div className="fixed bottom-0 left-0 right-0 bg-card border-t border-border shadow-lg z-50 animate-in slide-in-from-bottom">
      <div className="container mx-auto px-4 py-3">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <BarChart3 className="h-5 w-5 text-primary" />
            <span className="font-medium text-foreground">
              Comparar ({products.length}/4)
            </span>
          </div>

          <div className="flex-1 flex items-center gap-2 overflow-x-auto">
            {products.map((product) => (
              <div
                key={product.id}
                className="flex items-center gap-2 bg-muted rounded-lg px-3 py-2 flex-shrink-0"
              >
                <img
                  src={product.image}
                  alt={product.name}
                  className="w-8 h-8 rounded object-cover"
                />
                <span className="text-sm text-foreground max-w-[120px] truncate">
                  {product.name}
                </span>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-6 w-6"
                  onClick={() => removeProduct(product.id)}
                >
                  <X className="h-3 w-3" />
                </Button>
              </div>
            ))}
          </div>

          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={clearAll}>
              Limpar
            </Button>
            <Button
              size="sm"
              disabled={products.length < 2}
              onClick={() => navigate('/marketplace/compare')}
            >
              Comparar Produtos
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};
