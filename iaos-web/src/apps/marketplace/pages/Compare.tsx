import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Check, X, ShoppingCart } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { CatalogHeader } from '@/apps/marketplace/components/catalog/CatalogHeader';
import { useCompare } from '@/apps/marketplace/hooks/useCompare';
import { useCart } from '@/apps/marketplace/hooks/useCart';
import { useToast } from '@/hooks/use-toast';
import { useState } from 'react';

const Compare = () => {
  const navigate = useNavigate();
  const { products, removeProduct, clearAll } = useCompare();
  const { addItem } = useCart();
  const { toast } = useToast();
  const [searchQuery, setSearchQuery] = useState('');

  if (products.length < 2) {
    return (
      <div className="min-h-screen bg-background">
        <CatalogHeader
          searchQuery={searchQuery}
          onSearchChange={setSearchQuery}
          onSearch={() => navigate('/marketplace')}
        />
        <div className="container mx-auto px-4 py-16 text-center">
          <h1 className="text-2xl font-bold mb-4">Selecione pelo menos 2 produtos para comparar</h1>
          <Button onClick={() => navigate('/marketplace')}>Voltar ao Catálogo</Button>
        </div>
      </div>
    );
  }

  // Get all unique spec labels
  const allSpecs = [...new Set(products.flatMap((p) => p.specs.map((s) => s.label)))];

  const handleAddToCart = (product: typeof products[0]) => {
    addItem(product, product.minOrder, product.price);
    toast({
      title: 'Adicionado ao carrinho!',
      description: `${product.minOrder}x ${product.name}`,
    });
  };

  return (
    <div className="min-h-screen bg-background">
      <CatalogHeader
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
        onSearch={() => navigate('/marketplace')}
      />

      <div className="bg-card border-b border-border">
        <div className="container mx-auto px-4 py-3">
          <nav className="flex items-center gap-2 text-sm text-muted-foreground">
            <button
              onClick={() => navigate('/marketplace')}
              className="flex items-center gap-1 hover:text-primary transition-colors"
            >
              <ArrowLeft className="h-4 w-4" />
              Voltar
            </button>
            <span className="mx-2">/</span>
            <span className="text-foreground">Comparação de Produtos</span>
          </nav>
        </div>
      </div>

      <main className="container mx-auto px-4 py-8">
        <div className="flex items-center justify-between mb-8">
          <h1 className="text-2xl font-bold text-foreground">
            Comparar Produtos ({products.length})
          </h1>
          <Button variant="outline" onClick={clearAll}>
            Limpar Comparação
          </Button>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full border-collapse">
            <thead>
              <tr>
                <th className="text-left p-4 bg-muted/50 border-b border-border min-w-[200px]">
                  Característica
                </th>
                {products.map((product) => (
                  <th
                    key={product.id}
                    className="p-4 bg-muted/50 border-b border-border min-w-[250px]"
                  >
                    <div className="space-y-3">
                      <div className="relative">
                        <Button
                          variant="ghost"
                          size="icon"
                          className="absolute -top-2 -right-2 h-6 w-6"
                          onClick={() => removeProduct(product.id)}
                        >
                          <X className="h-4 w-4" />
                        </Button>
                        <img
                          src={product.image}
                          alt={product.name}
                          className="w-32 h-32 object-cover rounded-lg mx-auto"
                        />
                      </div>
                      <h3 className="font-medium text-foreground text-sm line-clamp-2">
                        {product.name}
                      </h3>
                      <p className="text-lg font-bold text-primary">
                        R$ {product.price.toLocaleString('pt-BR')}
                      </p>
                      <Button
                        size="sm"
                        className="w-full"
                        onClick={() => handleAddToCart(product)}
                      >
                        <ShoppingCart className="h-4 w-4 mr-2" />
                        Adicionar
                      </Button>
                    </div>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {/* Basic Info */}
              <tr>
                <td className="p-4 border-b border-border font-medium text-muted-foreground">
                  Parceiro
                </td>
                {products.map((product) => (
                  <td key={product.id} className="p-4 border-b border-border text-center">
                    <span className="text-primary font-medium">{product.partner}</span>
                  </td>
                ))}
              </tr>
              <tr>
                <td className="p-4 border-b border-border font-medium text-muted-foreground">
                  Condição
                </td>
                {products.map((product) => (
                  <td key={product.id} className="p-4 border-b border-border text-center">
                    <Badge variant={product.condition === 'new' ? 'default' : 'secondary'}>
                      {product.condition === 'new' ? 'Novo' : product.condition}
                    </Badge>
                  </td>
                ))}
              </tr>
              <tr>
                <td className="p-4 border-b border-border font-medium text-muted-foreground">
                  Estoque
                </td>
                {products.map((product) => (
                  <td key={product.id} className="p-4 border-b border-border text-center">
                    {product.stockStatus === 'available' ? (
                      <span className="text-accent flex items-center justify-center gap-1">
                        <Check className="h-4 w-4" /> {product.stock} unid.
                      </span>
                    ) : product.stockStatus === 'low' ? (
                      <span className="text-urgency">Últimas {product.stock} unid.</span>
                    ) : (
                      <span className="text-destructive">Esgotado</span>
                    )}
                  </td>
                ))}
              </tr>
              <tr>
                <td className="p-4 border-b border-border font-medium text-muted-foreground">
                  Garantia
                </td>
                {products.map((product) => (
                  <td key={product.id} className="p-4 border-b border-border text-center">
                    {product.warranty}
                  </td>
                ))}
              </tr>
              <tr>
                <td className="p-4 border-b border-border font-medium text-muted-foreground">
                  Prazo de Entrega
                </td>
                {products.map((product) => (
                  <td key={product.id} className="p-4 border-b border-border text-center">
                    {product.leadTime}
                  </td>
                ))}
              </tr>

              {/* Technical Specs */}
              {allSpecs.map((specLabel) => (
                <tr key={specLabel}>
                  <td className="p-4 border-b border-border font-medium text-muted-foreground">
                    {specLabel}
                  </td>
                  {products.map((product) => {
                    const spec = product.specs.find((s) => s.label === specLabel);
                    return (
                      <td key={product.id} className="p-4 border-b border-border text-center">
                        {spec?.value || '-'}
                      </td>
                    );
                  })}
                </tr>
              ))}

              {/* Certifications */}
              <tr>
                <td className="p-4 border-b border-border font-medium text-muted-foreground">
                  Certificações
                </td>
                {products.map((product) => (
                  <td key={product.id} className="p-4 border-b border-border text-center">
                    <div className="flex flex-wrap gap-1 justify-center">
                      {product.certifications.map((cert) => (
                        <Badge key={cert} variant="outline" className="text-xs">
                          {cert}
                        </Badge>
                      ))}
                    </div>
                  </td>
                ))}
              </tr>
            </tbody>
          </table>
        </div>
      </main>
    </div>
  );
};

export default Compare;
