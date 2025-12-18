import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { 
  ArrowLeft, 
  Heart, 
  Share2, 
  Star, 
  Shield, 
  Truck, 
  Package, 
  AlertTriangle, 
  XCircle,
  ShoppingCart,
  MessageSquare,
  Check,
  Tag,
  Clock,
  Award,
  TrendingUp,
  Users,
  Minus,
  BarChart3,
  Plus
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { CatalogHeader } from '@/apps/marketplace/components/catalog/CatalogHeader';
import { ProductGallery } from '@/apps/marketplace/components/product/ProductGallery';
import { VolumePricingTable } from '@/apps/marketplace/components/product/VolumePricingTable';
import { NegotiationForm } from '@/apps/marketplace/components/product/NegotiationForm';
import { SellerDescription } from '@/apps/marketplace/components/product/SellerDescription';
import { SellerContact } from '@/apps/marketplace/components/product/SellerContact';
import { SimilarPartners } from '@/apps/marketplace/components/product/SimilarPartners';
import { mockProducts } from '@/apps/marketplace/data/mockData';
import { useToast } from '@/hooks/use-toast';
import { useCart } from '@/apps/marketplace/hooks/useCart';
import { useCompare } from '@/apps/marketplace/hooks/useCompare';

const ProductDetails = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const { toast } = useToast();
  const { addItem } = useCart();
  const { addProduct, isComparing, removeProduct } = useCompare();
  const [searchQuery, setSearchQuery] = useState('');
  const [quantity, setQuantity] = useState(1);
  const [isFavorite, setIsFavorite] = useState(false);

  const product = mockProducts.find((p) => p.id === id);

  if (!product) {
    return (
      <div className="min-h-screen bg-background flex items-center justify-center">
        <div className="text-center">
          <h1 className="text-2xl font-bold mb-4">Produto não encontrado</h1>
          <Button onClick={() => navigate('/marketplace')}>Voltar ao Catálogo</Button>
        </div>
      </div>
    );
  }

  const discount = product.originalPrice
    ? Math.round(((product.originalPrice - product.price) / product.originalPrice) * 100)
    : 0;

  const getCurrentPrice = () => {
    for (let i = product.volumePricing.length - 1; i >= 0; i--) {
      if (quantity >= product.volumePricing[i].minQty) {
        return product.volumePricing[i];
      }
    }
    return product.volumePricing[0];
  };

  const currentPricing = getCurrentPrice();
  const totalPrice = currentPricing.price * quantity;

  const getStockBadge = () => {
    switch (product.stockStatus) {
      case 'available':
        return (
          <Badge className="badge-stock-available">
            <Package className="h-3 w-3 mr-1" />
            {product.stock} unidades disponíveis
          </Badge>
        );
      case 'low':
        return (
          <Badge className="badge-stock-low animate-pulse-subtle">
            <AlertTriangle className="h-3 w-3 mr-1" />
            Últimas {product.stock} unidades!
          </Badge>
        );
      case 'out':
        return (
          <Badge className="badge-stock-out">
            <XCircle className="h-3 w-3 mr-1" />
            Esgotado
          </Badge>
        );
    }
  };

  const handleAddToCart = () => {
    addItem(product, quantity, currentPricing.price);
    toast({
      title: 'Adicionado ao carrinho!',
      description: `${quantity}x ${product.name}`,
    });
  };

  const handleCompare = () => {
    if (isComparing(product.id)) {
      removeProduct(product.id);
    } else {
      addProduct(product);
    }
  };

  const handleToggleFavorite = () => {
    setIsFavorite(!isFavorite);
    toast({
      title: isFavorite ? 'Removido dos favoritos' : 'Adicionado aos favoritos',
      description: product.name,
    });
  };

  return (
    <div className="min-h-screen bg-background">
      <CatalogHeader
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
        onSearch={() => navigate('/marketplace')}
      />

      {/* Breadcrumb */}
      <div className="bg-card border-b border-border">
        <div className="container mx-auto px-4 py-3">
          <nav className="text-sm text-muted-foreground flex items-center gap-2">
            <button onClick={() => navigate('/marketplace')} className="flex items-center gap-1 hover:text-primary transition-colors">
              <ArrowLeft className="h-4 w-4" />
              Voltar
            </button>
            <span className="mx-2">/</span>
            <span className="hover:text-primary cursor-pointer">Catálogo</span>
            <span className="mx-2">/</span>
            <span className="text-foreground capitalize">{product.category}</span>
          </nav>
        </div>
      </div>

      <main className="container mx-auto px-4 py-8">
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
          {/* Left Column - Gallery */}
          <div className="lg:col-span-7">
            <ProductGallery
              images={product.images}
              productName={product.name}
              discount={discount}
              inCarts={product.inCarts}
            />

            {/* Product Details Tabs */}
            <div className="mt-8">
              <Tabs defaultValue="specs" className="w-full">
                <TabsList className="w-full justify-start bg-muted/50 p-1 rounded-lg">
                  <TabsTrigger value="specs" className="flex-1">Especificações</TabsTrigger>
                  <TabsTrigger value="description" className="flex-1">Descrição</TabsTrigger>
                  <TabsTrigger value="shipping" className="flex-1">Envio</TabsTrigger>
                </TabsList>
                <TabsContent value="specs" className="mt-4">
                  <div className="bg-card border border-border rounded-xl p-6">
                    <h3 className="font-semibold text-foreground mb-4">Especificações Técnicas</h3>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      {product.specs.map((spec, index) => (
                        <div key={index} className="flex justify-between py-2 border-b border-border last:border-0">
                          <span className="text-muted-foreground">{spec.label}</span>
                          <span className="font-medium text-foreground">{spec.value}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                </TabsContent>
                <TabsContent value="description" className="mt-4">
                  <div className="bg-card border border-border rounded-xl p-6">
                    <h3 className="font-semibold text-foreground mb-4">Descrição do Produto</h3>
                    <p className="text-muted-foreground leading-relaxed">{product.description}</p>
                    <div className="mt-4 flex flex-wrap gap-2">
                      {product.tags.map((tag) => (
                        <Badge key={tag} variant="secondary">{tag}</Badge>
                      ))}
                    </div>
                  </div>
                </TabsContent>
                <TabsContent value="shipping" className="mt-4">
                  <div className="bg-card border border-border rounded-xl p-6 space-y-4">
                    <div className="flex items-start gap-3">
                      <Truck className="h-5 w-5 text-primary mt-0.5" />
                      <div>
                        <h4 className="font-medium text-foreground">Prazo de Entrega</h4>
                        <p className="text-muted-foreground">{product.leadTime}</p>
                      </div>
                    </div>
                    <div className="flex items-start gap-3">
                      <Shield className="h-5 w-5 text-primary mt-0.5" />
                      <div>
                        <h4 className="font-medium text-foreground">Garantia</h4>
                        <p className="text-muted-foreground">{product.warranty}</p>
                      </div>
                    </div>
                    <div className="flex items-start gap-3">
                      <Award className="h-5 w-5 text-primary mt-0.5" />
                      <div>
                        <h4 className="font-medium text-foreground">Certificações</h4>
                        <div className="flex flex-wrap gap-2 mt-1">
                          {product.certifications.map((cert) => (
                            <Badge key={cert} variant="outline">{cert}</Badge>
                          ))}
                        </div>
                      </div>
                    </div>
                  </div>
                </TabsContent>
              </Tabs>
            </div>
          </div>

          {/* Right Column - Product Info & Actions */}
          <div className="lg:col-span-5 space-y-6">
            {/* Product Title & Partner */}
            <div className="bg-card border border-border rounded-xl p-6 space-y-4">
              {/* Partner Info - eBay Style */}
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center">
                    <span className="font-bold text-primary">{product.partner.charAt(0)}</span>
                  </div>
                  <div>
                    <a href="#" className="font-medium text-primary hover:underline">
                      {product.partner}
                    </a>
                    <div className="flex items-center gap-2 text-sm">
                      <div className="flex items-center text-warning">
                        {[...Array(5)].map((_, i) => (
                          <Star
                            key={i}
                            className={`h-3 w-3 ${i < Math.floor(product.partnerRating) ? 'fill-current' : ''}`}
                          />
                        ))}
                      </div>
                      <span className="text-muted-foreground">
                        ({product.partnerRating}) · {product.partnerSales.toLocaleString('pt-BR')} vendas
                      </span>
                    </div>
                  </div>
                </div>
                <Button variant="outline" size="sm">Ver Loja</Button>
              </div>

              <Separator />

              {/* Title */}
              <h1 className="text-xl lg:text-2xl font-bold text-foreground leading-tight">
                {product.name}
              </h1>

              {/* Social Proof */}
              <div className="flex flex-wrap items-center gap-3 text-sm">
                {product.inCarts > 5 && (
                  <div className="flex items-center gap-1 text-popularity">
                    <Users className="h-4 w-4" />
                    <span className="font-medium">Em {product.inCarts} carrinhos</span>
                  </div>
                )}
                <div className="flex items-center gap-1 text-muted-foreground">
                  <TrendingUp className="h-4 w-4" />
                  <span>{product.soldCount} vendidos</span>
                </div>
              </div>

              {/* Stock Status */}
              <div className="flex items-center gap-3">
                {getStockBadge()}
                <span className="text-sm text-muted-foreground">
                  Condição: <span className="font-medium text-foreground capitalize">{product.condition === 'new' ? 'Novo' : product.condition}</span>
                </span>
              </div>
            </div>

            {/* Pricing Section */}
            <div className="bg-card border border-border rounded-xl p-6 space-y-4">
              {/* Price Display */}
              <div className="space-y-2">
                {product.originalPrice && (
                  <div className="flex items-center gap-2">
                    <span className="text-lg text-muted-foreground line-through">
                      R$ {product.originalPrice.toLocaleString('pt-BR')}
                    </span>
                    <Badge className="bg-destructive text-destructive-foreground">
                      -{discount}%
                    </Badge>
                  </div>
                )}
                <div className="flex items-baseline gap-2">
                  <span className="text-3xl font-bold text-foreground">
                    R$ {currentPricing.price.toLocaleString('pt-BR')}
                  </span>
                  <span className="text-muted-foreground">/unid.</span>
                </div>
                {currentPricing.discount > 0 && (
                  <p className="text-sm text-accent font-medium flex items-center gap-1">
                    <Tag className="h-4 w-4" />
                    Desconto de volume aplicado: -{currentPricing.discount}%
                  </p>
                )}
              </div>

              <Separator />

              {/* Quantity Selector */}
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <Label className="text-sm font-medium">Quantidade</Label>
                  {product.stockStatus === 'low' && (
                    <span className="text-sm text-urgency font-medium animate-pulse-subtle">
                      Últimas unidades!
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-4">
                  <div className="flex items-center border border-border rounded-lg overflow-hidden">
                    <Button
                      variant="ghost"
                      size="icon"
                      className="rounded-none h-10"
                      onClick={() => setQuantity(Math.max(product.minOrder, quantity - 1))}
                      disabled={quantity <= product.minOrder}
                    >
                      <Minus className="h-4 w-4" />
                    </Button>
                    <span className="w-16 text-center font-medium">{quantity}</span>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="rounded-none h-10"
                      onClick={() => setQuantity(Math.min(product.stock, quantity + 1))}
                      disabled={quantity >= product.stock || product.stockStatus === 'out'}
                    >
                      <Plus className="h-4 w-4" />
                    </Button>
                  </div>
                  {product.minOrder > 1 && (
                    <span className="text-sm text-muted-foreground">
                      Mín: {product.minOrder} unid.
                    </span>
                  )}
                </div>
              </div>

              {/* Total */}
              <div className="bg-muted/50 rounded-lg p-4">
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Total ({quantity} unid.)</span>
                  <span className="text-2xl font-bold text-foreground">
                    R$ {totalPrice.toLocaleString('pt-BR')}
                  </span>
                </div>
              </div>

              {/* CTA Buttons - eBay Style */}
              <div className="space-y-3">
                <Button
                  className="btn-primary-cta"
                  disabled={product.stockStatus === 'out'}
                  onClick={handleAddToCart}
                >
                  <ShoppingCart className="h-5 w-5 mr-2" />
                  Adicionar ao Carrinho
                </Button>
                <Button
                  className="btn-secondary-cta"
                  disabled={product.stockStatus === 'out'}
                >
                  <MessageSquare className="h-5 w-5 mr-2" />
                  Solicitar Cotação
                </Button>
                <Button
                  className="btn-tertiary-cta"
                  onClick={handleToggleFavorite}
                >
                  <Heart className={`h-5 w-5 mr-2 ${isFavorite ? 'fill-current text-urgency' : ''}`} />
                  {isFavorite ? 'Adicionado aos Favoritos' : 'Adicionar aos Favoritos'}
                </Button>
              </div>

              {/* Trust Badges */}
              <div className="flex flex-wrap gap-4 pt-2">
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                  <Shield className="h-4 w-4 text-accent" />
                  Compra Segura
                </div>
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                  <Truck className="h-4 w-4 text-accent" />
                  {product.leadTime}
                </div>
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                  <Clock className="h-4 w-4 text-accent" />
                  {product.warranty}
                </div>
              </div>
            </div>

            {/* Volume Pricing */}
            <VolumePricingTable
              pricing={product.volumePricing}
              selectedQty={quantity}
              onSelectQty={setQuantity}
              currency={product.currency}
            />

            {/* Negotiation Form */}
            <NegotiationForm product={product} selectedQty={quantity} />

            {/* Compare Button */}
            <Button
              variant={isComparing(product.id) ? "default" : "outline"}
              className="w-full"
              onClick={handleCompare}
            >
              <BarChart3 className="h-4 w-4 mr-2" />
              {isComparing(product.id) ? 'Remover da Comparação' : 'Adicionar à Comparação'}
            </Button>
          </div>
        </div>

        {/* Seller Description Section */}
        <div className="mt-12">
          <SellerDescription product={product} />
        </div>

        {/* Seller Contact Section */}
        <div className="mt-8">
          <SellerContact product={product} />
        </div>

        {/* Similar Partners Section */}
        <div className="mt-8 mb-24">
          <SimilarPartners currentProduct={product} />
        </div>
      </main>
    </div>
  );
};

// Add Label component usage
const Label = ({ children, className }: { children: React.ReactNode; className?: string }) => (
  <label className={className}>{children}</label>
);

export default ProductDetails;
