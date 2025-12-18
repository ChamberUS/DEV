import { useState } from 'react';
import { Send, User, Mail, Phone, MessageSquare, Package } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { useToast } from '@/hooks/use-toast';
import { Product } from '@/apps/marketplace/types/product';

interface NegotiationFormProps {
  product: Product;
  selectedQty: number;
}

export const NegotiationForm = ({ product, selectedQty }: NegotiationFormProps) => {
  const { toast } = useToast();
  const [form, setForm] = useState({
    quantity: selectedQty,
    targetPrice: '',
    message: '',
    contactName: '',
    contactEmail: '',
    contactPhone: '',
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    console.log('Negociação enviada:', form);
    toast({
      title: 'Solicitação Enviada!',
      description: 'Entraremos em contato em até 24 horas úteis.',
    });
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  };

  return (
    <div className="bg-card border border-border rounded-xl overflow-hidden">
      <div className="p-4 bg-primary/5 border-b border-border">
        <h3 className="font-semibold text-foreground flex items-center gap-2">
          <MessageSquare className="h-5 w-5 text-primary" />
          Solicitar Negociação
        </h3>
        <p className="text-sm text-muted-foreground mt-1">
          Negocie preços especiais para grandes volumes
        </p>
      </div>
      
      <form onSubmit={handleSubmit} className="p-4 space-y-4">
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="quantity" className="flex items-center gap-2 text-sm">
              <Package className="h-4 w-4 text-muted-foreground" />
              Quantidade
            </Label>
            <Input
              id="quantity"
              name="quantity"
              type="number"
              min={product.minOrder}
              value={form.quantity}
              onChange={handleChange}
              className="h-10"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="targetPrice" className="text-sm">
              Preço Desejado (R$)
            </Label>
            <Input
              id="targetPrice"
              name="targetPrice"
              type="text"
              placeholder="Ex: 10.500,00"
              value={form.targetPrice}
              onChange={handleChange}
              className="h-10"
            />
          </div>
        </div>

        <div className="space-y-2">
          <Label htmlFor="contactName" className="flex items-center gap-2 text-sm">
            <User className="h-4 w-4 text-muted-foreground" />
            Nome Completo
          </Label>
          <Input
            id="contactName"
            name="contactName"
            type="text"
            placeholder="Seu nome"
            value={form.contactName}
            onChange={handleChange}
            className="h-10"
            required
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="contactEmail" className="flex items-center gap-2 text-sm">
              <Mail className="h-4 w-4 text-muted-foreground" />
              E-mail
            </Label>
            <Input
              id="contactEmail"
              name="contactEmail"
              type="email"
              placeholder="seu@email.com"
              value={form.contactEmail}
              onChange={handleChange}
              className="h-10"
              required
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="contactPhone" className="flex items-center gap-2 text-sm">
              <Phone className="h-4 w-4 text-muted-foreground" />
              Telefone
            </Label>
            <Input
              id="contactPhone"
              name="contactPhone"
              type="tel"
              placeholder="(11) 99999-9999"
              value={form.contactPhone}
              onChange={handleChange}
              className="h-10"
            />
          </div>
        </div>

        <div className="space-y-2">
          <Label htmlFor="message" className="text-sm">Mensagem (Opcional)</Label>
          <Textarea
            id="message"
            name="message"
            placeholder="Descreva sua necessidade, prazo de entrega desejado, etc."
            value={form.message}
            onChange={handleChange}
            rows={3}
          />
        </div>

        <Button type="submit" className="w-full btn-primary-cta">
          <Send className="h-4 w-4 mr-2" />
          Enviar Solicitação
        </Button>
      </form>
    </div>
  );
};
