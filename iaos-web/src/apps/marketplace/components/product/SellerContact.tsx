import { Star, Calendar, Package, MessageSquare, Heart, ExternalLink } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { Product } from '@/apps/marketplace/types/product';

interface SellerContactProps {
  product: Product;
}

export const SellerContact = ({ product }: SellerContactProps) => {
  const currentYear = new Date().getFullYear();
  const memberSince = currentYear - Math.floor(product.partnerSales / 5000);

  return (
    <div className="bg-card border border-border rounded-xl p-6">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        {/* About Seller */}
        <div className="space-y-4">
          <h3 className="text-xl font-bold text-foreground">Sobre este vendedor</h3>
          
          <div className="flex items-center gap-4">
            <div className="w-16 h-16 rounded-full bg-gradient-to-br from-primary/20 to-accent/20 flex items-center justify-center border-2 border-primary/20">
              <span className="text-2xl font-bold text-primary">
                {product.partner.charAt(0)}
              </span>
            </div>
            <div>
              <h4 className="font-semibold text-foreground text-lg">{product.partner}</h4>
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <span className="text-accent font-medium">
                  {Math.round(product.partnerRating * 20)}% de feedback positivo
                </span>
                <span>·</span>
                <span>{product.partnerSales.toLocaleString('pt-BR')} de itens vendidos</span>
              </div>
            </div>
          </div>

          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Calendar className="h-4 w-4" />
            <span>Cadastrado desde {memberSince}</span>
          </div>

          <div className="space-y-2">
            <Button className="w-full bg-primary hover:bg-primary/90">
              <Package className="h-4 w-4 mr-2" />
              Outros itens do vendedor
            </Button>
            <Button variant="outline" className="w-full">
              <MessageSquare className="h-4 w-4 mr-2" />
              Contatar
            </Button>
            <Button variant="ghost" className="w-full">
              <Heart className="h-4 w-4 mr-2" />
              Salvar vendedor
            </Button>
          </div>
        </div>

        {/* Seller Feedback */}
        <div className="space-y-4">
          <div className="flex items-center gap-2">
            <h3 className="text-xl font-bold text-foreground">Feedback sobre o vendedor</h3>
            <Badge variant="secondary" className="text-xs">
              {Math.ceil(product.soldCount / 10)}
            </Badge>
          </div>

          <div className="flex items-center gap-4 border-b border-border pb-4">
            <button className="text-sm text-muted-foreground hover:text-foreground pb-2 border-b-2 border-transparent">
              Este item ({Math.ceil(product.soldCount / 20)})
            </button>
            <button className="text-sm font-medium text-foreground pb-2 border-b-2 border-primary">
              Todos os itens ({Math.ceil(product.soldCount / 10)})
            </button>
          </div>

          <div className="space-y-3">
            {/* Sample Feedback */}
            <div className="p-3 bg-muted/50 rounded-lg space-y-2">
              <div className="flex items-center gap-2">
                <div className="flex items-center text-accent">
                  <span className="text-sm font-medium">⊕</span>
                </div>
                <span className="text-xs text-muted-foreground">Avaliação automática · Último mês</span>
              </div>
              <p className="text-sm text-muted-foreground">
                Pedido entregue no prazo, sem problemas.
              </p>
              <a href="#" className="text-xs text-primary hover:underline">
                {product.name.substring(0, 50)}...
              </a>
            </div>

            <Button variant="outline" size="sm" className="text-primary">
              Ver todos os feedbacks
              <ExternalLink className="h-3 w-3 ml-2" />
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};
