import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Building2, User, Mail, Phone, MapPin, FileText } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Separator } from '@/components/ui/separator';
import { CatalogHeader } from '@/apps/marketplace/components/catalog/CatalogHeader';
import { useCart } from '@/apps/marketplace/hooks/useCart';
import { useToast } from '@/hooks/use-toast';

const Checkout = () => {
  const navigate = useNavigate();
  const { items, total, clearCart } = useCart();
  const { toast } = useToast();
  const [searchQuery, setSearchQuery] = useState('');

  const [formData, setFormData] = useState({
    companyName: '',
    cnpj: '',
    contactName: '',
    email: '',
    phone: '',
    address: '',
    city: '',
    state: '',
    zipCode: '',
    notes: '',
  });

  if (items.length === 0) {
    return (
      <div className="min-h-screen bg-background">
        <CatalogHeader
          searchQuery={searchQuery}
          onSearchChange={setSearchQuery}
          onSearch={() => navigate('/marketplace')}
        />
        <div className="container mx-auto px-4 py-16 text-center">
          <h1 className="text-2xl font-bold mb-4">Seu carrinho está vazio</h1>
          <Button onClick={() => navigate('/marketplace')}>Voltar ao Catálogo</Button>
        </div>
      </div>
    );
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    toast({
      title: 'Cotação enviada com sucesso!',
      description: 'Nossa equipe entrará em contato em até 24h úteis.',
    });
    clearCart();
    navigate('/marketplace');
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    setFormData((prev) => ({ ...prev, [e.target.name]: e.target.value }));
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
            <span className="text-foreground">Checkout B2B</span>
          </nav>
        </div>
      </div>

      <main className="container mx-auto px-4 py-8">
        <h1 className="text-2xl font-bold text-foreground mb-8">Finalizar Cotação B2B</h1>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
          {/* Form */}
          <div className="lg:col-span-2">
            <form onSubmit={handleSubmit} className="space-y-6">
              {/* Company Info */}
              <div className="bg-card border border-border rounded-xl p-6 space-y-4">
                <h2 className="font-semibold text-lg flex items-center gap-2">
                  <Building2 className="h-5 w-5 text-primary" />
                  Dados da Empresa
                </h2>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label htmlFor="companyName">Razão Social *</Label>
                    <Input
                      id="companyName"
                      name="companyName"
                      value={formData.companyName}
                      onChange={handleChange}
                      required
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="cnpj">CNPJ *</Label>
                    <Input
                      id="cnpj"
                      name="cnpj"
                      value={formData.cnpj}
                      onChange={handleChange}
                      required
                    />
                  </div>
                </div>
              </div>

              {/* Contact Info */}
              <div className="bg-card border border-border rounded-xl p-6 space-y-4">
                <h2 className="font-semibold text-lg flex items-center gap-2">
                  <User className="h-5 w-5 text-primary" />
                  Contato
                </h2>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label htmlFor="contactName">Nome do Responsável *</Label>
                    <Input
                      id="contactName"
                      name="contactName"
                      value={formData.contactName}
                      onChange={handleChange}
                      required
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="email">E-mail Corporativo *</Label>
                    <Input
                      id="email"
                      name="email"
                      type="email"
                      value={formData.email}
                      onChange={handleChange}
                      required
                    />
                  </div>
                  <div className="space-y-2 md:col-span-2">
                    <Label htmlFor="phone">Telefone *</Label>
                    <Input
                      id="phone"
                      name="phone"
                      value={formData.phone}
                      onChange={handleChange}
                      required
                    />
                  </div>
                </div>
              </div>

              {/* Address */}
              <div className="bg-card border border-border rounded-xl p-6 space-y-4">
                <h2 className="font-semibold text-lg flex items-center gap-2">
                  <MapPin className="h-5 w-5 text-primary" />
                  Endereço de Entrega
                </h2>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="space-y-2 md:col-span-2">
                    <Label htmlFor="address">Endereço Completo *</Label>
                    <Input
                      id="address"
                      name="address"
                      value={formData.address}
                      onChange={handleChange}
                      required
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="city">Cidade *</Label>
                    <Input
                      id="city"
                      name="city"
                      value={formData.city}
                      onChange={handleChange}
                      required
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="state">Estado *</Label>
                    <Input
                      id="state"
                      name="state"
                      value={formData.state}
                      onChange={handleChange}
                      required
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="zipCode">CEP *</Label>
                    <Input
                      id="zipCode"
                      name="zipCode"
                      value={formData.zipCode}
                      onChange={handleChange}
                      required
                    />
                  </div>
                </div>
              </div>

              {/* Notes */}
              <div className="bg-card border border-border rounded-xl p-6 space-y-4">
                <h2 className="font-semibold text-lg flex items-center gap-2">
                  <FileText className="h-5 w-5 text-primary" />
                  Observações
                </h2>
                <Textarea
                  name="notes"
                  value={formData.notes}
                  onChange={handleChange}
                  placeholder="Informações adicionais, condições especiais, prazos, etc."
                  rows={4}
                />
              </div>

              <Button type="submit" size="lg" className="w-full">
                Enviar Cotação B2B
              </Button>
            </form>
          </div>

          {/* Order Summary */}
          <div className="lg:col-span-1">
            <div className="bg-card border border-border rounded-xl p-6 sticky top-4 space-y-4">
              <h2 className="font-semibold text-lg">Resumo do Pedido</h2>
              
              <div className="space-y-3 max-h-[400px] overflow-y-auto">
                {items.map((item) => (
                  <div key={item.product.id} className="flex gap-3 pb-3 border-b border-border">
                    <img
                      src={item.product.image}
                      alt={item.product.name}
                      className="w-16 h-16 rounded object-cover"
                    />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium line-clamp-2">{item.product.name}</p>
                      <p className="text-xs text-muted-foreground">{item.quantity}x</p>
                      <p className="text-sm font-semibold">
                        R$ {(item.unitPrice * item.quantity).toLocaleString('pt-BR')}
                      </p>
                    </div>
                  </div>
                ))}
              </div>

              <Separator />

              <div className="space-y-2">
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Subtotal</span>
                  <span>R$ {total.toLocaleString('pt-BR')}</span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Frete</span>
                  <span className="text-accent">A calcular</span>
                </div>
                <Separator />
                <div className="flex justify-between font-bold text-lg">
                  <span>Total</span>
                  <span>R$ {total.toLocaleString('pt-BR')}</span>
                </div>
              </div>

              <p className="text-xs text-muted-foreground text-center">
                *Valores sujeitos a confirmação após análise da cotação
              </p>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
};

export default Checkout;
