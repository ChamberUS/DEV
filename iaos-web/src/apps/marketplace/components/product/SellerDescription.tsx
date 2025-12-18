import { Badge } from '@/components/ui/badge';
import { Product } from '@/apps/marketplace/types/product';

interface SellerDescriptionProps {
  product: Product;
}

export const SellerDescription = ({ product }: SellerDescriptionProps) => {
  return (
    <div className="bg-card border border-border rounded-xl p-6 space-y-6">
      <h2 className="text-xl font-bold text-foreground">
        Descrição do item dada pelo vendedor
      </h2>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        {/* Product Image and Title */}
        <div className="space-y-4">
          <div className="aspect-square bg-muted rounded-lg overflow-hidden">
            <img
              src={product.images[0]}
              alt={product.name}
              className="w-full h-full object-contain"
            />
          </div>
        </div>

        {/* Description Content */}
        <div className="space-y-4">
          <h3 className="text-lg font-semibold text-muted-foreground uppercase tracking-wide">
            {product.name}
          </h3>
          
          <p className="text-muted-foreground leading-relaxed">
            {product.description}
          </p>

          {/* Included Items */}
          <div className="pt-4 border-t border-border">
            <h4 className="font-semibold text-foreground mb-2">Itens Inclusos</h4>
            <p className="text-muted-foreground text-sm">
              {product.name} - {product.specs.map(s => s.value).slice(0, 3).join(' - ')}
            </p>
          </div>

          {/* Features */}
          <div className="pt-4 border-t border-border">
            <h4 className="font-semibold text-foreground mb-3">Características</h4>
            <div className="space-y-3">
              {product.tags.map((tag, index) => (
                <div key={index}>
                  <span className="font-medium text-foreground">{tag}</span>
                  <p className="text-muted-foreground text-sm">
                    Característica destacada do produto para uso profissional e empresarial.
                  </p>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Specifications Table */}
      <div className="pt-6 border-t border-border">
        <h4 className="font-semibold text-foreground mb-4">Especificações</h4>
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          {product.specs.map((spec, index) => (
            <div key={index} className="flex flex-col">
              <span className="text-sm text-muted-foreground">{spec.label}:</span>
              <span className="font-medium text-foreground">{spec.value}</span>
            </div>
          ))}
          <div className="flex flex-col">
            <span className="text-sm text-muted-foreground">Condição:</span>
            <span className="font-medium text-foreground capitalize">
              {product.condition === 'new' ? 'Novo' : product.condition}
            </span>
          </div>
          <div className="flex flex-col">
            <span className="text-sm text-muted-foreground">País de Origem:</span>
            <span className="font-medium text-foreground">Brasil</span>
          </div>
        </div>
      </div>

      {/* Category Breadcrumb */}
      <div className="flex items-center gap-2 text-sm">
        <span className="text-muted-foreground">Categoria:</span>
        <a href="#" className="text-primary hover:underline capitalize">{product.category}</a>
        <span className="text-muted-foreground">›</span>
        <a href="#" className="text-primary hover:underline">Equipamentos Empresariais</a>
      </div>

      {/* Tags */}
      <div className="flex flex-wrap gap-2">
        {product.tags.map((tag) => (
          <Badge key={tag} variant="secondary" className="text-xs">
            {tag}
          </Badge>
        ))}
      </div>
    </div>
  );
};
