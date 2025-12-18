import { useParams, useNavigate } from "react-router-dom";
import { ArrowLeft, Package, Truck, CreditCard, MapPin } from "lucide-react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { Input } from "@/components/ui/input";

const orderData = {
  id: "#NEG-2024-001",
  retailer: "Magazine Luiza",
  retailerEmail: "parceiro@magalu.com.br",
  paymentStatus: "Pago",
  fulfillmentStatus: "Entregue",
  deliveryType: "Expresso",
  date: "10/12/2024",
  address: "Av. Paulista, 1500 - São Paulo, SP, 01310-100",
};

const items = [
  { name: "iPhone 15 Pro Max", quantity: 10, price: "R$ 7.499,00", total: "R$ 74.990,00" },
  { name: "AirPods Pro 2", quantity: 20, price: "R$ 1.899,00", total: "R$ 37.980,00" },
  { name: "Apple Watch Series 9", quantity: 5, price: "R$ 4.299,00", total: "R$ 21.495,00" },
];

const summary = {
  subtotal: "R$ 134.465,00",
  discount: "- R$ 13.446,50",
  shipping: "R$ 0,00",
  tax: "R$ 15.871,50",
  total: "R$ 136.890,00",
};

export default function NegotiationDetails() {
  const { id } = useParams();
  const navigate = useNavigate();

  return (
    <DashboardLayout>
      <div className="space-y-6">
        {/* Back Button */}
        <Button
          variant="ghost"
          className="gap-2"
          onClick={() => navigate("/admin/negociacoes")}
        >
          <ArrowLeft size={18} />
          Voltar para Negociações
        </Button>

        {/* Header */}
        <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
          <div>
            <div className="flex items-center gap-3">
              <h1 className="text-2xl font-bold text-foreground">{orderData.id}</h1>
              <Badge className="bg-success/10 text-success">
                {orderData.paymentStatus}
              </Badge>
              <Badge className="bg-success/10 text-success">
                {orderData.fulfillmentStatus}
              </Badge>
            </div>
            <p className="text-muted-foreground mt-1">
              Criado em {orderData.date}
            </p>
          </div>
          <div className="flex gap-3">
            <Button variant="outline">Imprimir</Button>
            <Button variant="destructive">Processar Reembolso</Button>
          </div>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Main Content */}
          <div className="lg:col-span-2 space-y-6">
            {/* Items */}
            <div className="bg-card rounded-xl shadow-card overflow-hidden animate-fade-in">
              <div className="p-5 border-b border-border">
                <h3 className="font-semibold text-card-foreground flex items-center gap-2">
                  <Package size={18} />
                  Itens da Negociação
                </h3>
              </div>
              <table className="w-full">
                <thead className="bg-muted/50">
                  <tr>
                    <th className="text-left py-3 px-4 text-sm font-medium text-muted-foreground">
                      Produto
                    </th>
                    <th className="text-center py-3 px-4 text-sm font-medium text-muted-foreground">
                      Qtd
                    </th>
                    <th className="text-right py-3 px-4 text-sm font-medium text-muted-foreground">
                      Preço Unit.
                    </th>
                    <th className="text-right py-3 px-4 text-sm font-medium text-muted-foreground">
                      Total
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((item) => (
                    <tr key={item.name} className="border-t border-border">
                      <td className="py-4 px-4 font-medium text-card-foreground">
                        {item.name}
                      </td>
                      <td className="py-4 px-4 text-center text-muted-foreground">
                        {item.quantity}
                      </td>
                      <td className="py-4 px-4 text-right text-muted-foreground">
                        {item.price}
                      </td>
                      <td className="py-4 px-4 text-right font-medium text-card-foreground">
                        {item.total}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Summary */}
            <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
              <h3 className="font-semibold text-card-foreground flex items-center gap-2 mb-4">
                <CreditCard size={18} />
                Resumo Financeiro
              </h3>
              <div className="space-y-3">
                <div className="flex justify-between text-muted-foreground">
                  <span>Subtotal</span>
                  <span>{summary.subtotal}</span>
                </div>
                <div className="flex justify-between text-success">
                  <span>Desconto B2B (10%)</span>
                  <span>{summary.discount}</span>
                </div>
                <div className="flex justify-between text-muted-foreground">
                  <span>Frete</span>
                  <span>{summary.shipping}</span>
                </div>
                <div className="flex justify-between text-muted-foreground">
                  <span>Impostos</span>
                  <span>{summary.tax}</span>
                </div>
                <Separator />
                <div className="flex justify-between text-lg font-bold text-card-foreground">
                  <span>Total</span>
                  <span>{summary.total}</span>
                </div>
              </div>
            </div>

            {/* Refund Section */}
            <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
              <h3 className="font-semibold text-card-foreground mb-4">
                Processar Reembolso
              </h3>
              <div className="flex gap-4">
                <Input placeholder="Valor do reembolso (R$)" className="max-w-xs" />
                <Button variant="destructive">Reembolsar</Button>
              </div>
              <p className="text-sm text-muted-foreground mt-2">
                O valor será devolvido ao método de pagamento original do lojista.
              </p>
            </div>
          </div>

          {/* Sidebar */}
          <div className="space-y-6">
            {/* Customer Info */}
            <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
              <h3 className="font-semibold text-card-foreground mb-4">
                Informações do Lojista
              </h3>
              <div className="flex items-center gap-3 mb-4">
                <div className="w-12 h-12 rounded-full bg-primary/10 flex items-center justify-center">
                  <span className="font-bold text-primary">
                    {orderData.retailer.charAt(0)}
                  </span>
                </div>
                <div>
                  <p className="font-medium text-card-foreground">
                    {orderData.retailer}
                  </p>
                  <p className="text-sm text-muted-foreground">
                    {orderData.retailerEmail}
                  </p>
                </div>
              </div>
              <Button variant="outline" className="w-full">
                Ver Perfil do Lojista
              </Button>
            </div>

            {/* Shipping Info */}
            <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
              <h3 className="font-semibold text-card-foreground flex items-center gap-2 mb-4">
                <Truck size={18} />
                Informações de Entrega
              </h3>
              <div className="space-y-3">
                <div>
                  <p className="text-sm text-muted-foreground">Tipo</p>
                  <p className="font-medium text-card-foreground">
                    {orderData.deliveryType}
                  </p>
                </div>
                <div>
                  <p className="text-sm text-muted-foreground">Status</p>
                  <Badge className="bg-success/10 text-success mt-1">
                    {orderData.fulfillmentStatus}
                  </Badge>
                </div>
              </div>
            </div>

            {/* Address */}
            <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
              <h3 className="font-semibold text-card-foreground flex items-center gap-2 mb-4">
                <MapPin size={18} />
                Endereço de Entrega
              </h3>
              <p className="text-muted-foreground">{orderData.address}</p>
            </div>
          </div>
        </div>
      </div>
    </DashboardLayout>
  );
}
