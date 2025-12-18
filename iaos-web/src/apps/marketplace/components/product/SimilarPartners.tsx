import { ExternalLink, Star } from 'lucide-react';
import { Product } from '@/apps/marketplace/types/product';
import { mockProducts } from '@/apps/marketplace/data/mockData';
import { Badge } from '@/components/ui/badge';

interface SimilarPartnersProps {
  currentProduct: Product;
}

export const SimilarPartners = ({ currentProduct }: SimilarPartnersProps) => {
  // Get other partners with similar products
  const similarPartners = mockProducts
    .filter(p => p.partner !== currentProduct.partner && p.category === currentProduct.category)
    .reduce((acc, product) => {
      if (!acc.find(p => p.partner === product.partner)) {
        acc.push(product);
      }
      return acc;
    }, [] as Product[])
    .slice(0, 3);

  if (similarPartners.length === 0) return null;

  return (
    <div className="bg-card border border-border rounded-xl p-6 space-y-6">
      <h2 className="text-xl font-bold text-foreground">
        Itens similares de outras lojas B2B
      </h2>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {similarPartners.map((partner) => (
          <div
            key={partner.id}
            className="border border-border rounded-lg p-4 hover:border-primary/50 transition-colors"
          >
            <div className="flex items-start gap-4">
              <div className="w-20 h-20 rounded-lg overflow-hidden bg-muted flex-shrink-0">
                <img
                  src={partner.image}
                  alt={partner.partner}
                  className="w-full h-full object-cover"
                />
              </div>
              <div className="flex-1 min-w-0">
                <h4 className="font-semibold text-foreground truncate">{partner.partner}</h4>
                <div className="flex items-center gap-1 text-sm text-muted-foreground mt-1">
                  <span className="font-medium">{partner.partnerSales.toLocaleString('pt-BR')}</span>
                  <span>itens vendidos</span>
                </div>
                <div className="flex items-center gap-1 text-sm text-accent mt-1">
                  <Star className="h-3 w-3 fill-current" />
                  <span className="font-medium">{Math.round(partner.partnerRating * 20)}% positivo</span>
                </div>
              </div>
            </div>

            <a
              href={`/marketplace/product/${partner.id}`}
              className="mt-4 flex items-center justify-center gap-2 text-primary hover:text-primary/80 font-medium text-sm"
            >
              Ver loja no catálogo
              <ExternalLink className="h-4 w-4" />
            </a>

            {/* Featured Product Preview */}
            <div className="mt-4 pt-4 border-t border-border">
              <div className="flex items-center gap-3">
                <div className="w-12 h-12 rounded bg-muted overflow-hidden">
                  <img
                    src={partner.image}
                    alt={partner.name}
                    className="w-full h-full object-cover"
                  />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-xs text-muted-foreground truncate">{partner.name}</p>
                  <p className="text-sm font-medium text-foreground">
                    R$ {partner.price.toLocaleString('pt-BR')}
                  </p>
                </div>
              </div>
            </div>

            <Badge variant="outline" className="mt-3 text-xs">
              Patrocinado
            </Badge>
          </div>
        ))}
      </div>
    </div>
  );
};
