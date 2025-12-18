import { useParams, useNavigate } from "react-router-dom";
import { ArrowLeft, Mail, Phone, MapPin, Calendar, Package, Edit } from "lucide-react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";

const retailerData = {
  id: 1,
  name: "Magazine Luiza",
  email: "parceiro@magalu.com.br",
  phone: "+55 11 3003-3003",
  address: "Av. Paulista, 1500 - São Paulo, SP",
  totalNegotiations: 156,
  totalValue: "R$ 245.000",
  status: "Ativo",
  joinedAt: "15 de Janeiro, 2024",
};

const orders = [
  { id: "#NEG-001", date: "10/12/2024", value: "R$ 15.000", status: "Entregue" },
  { id: "#NEG-002", date: "08/12/2024", value: "R$ 8.500", status: "Em Transporte" },
  { id: "#NEG-003", date: "05/12/2024", value: "R$ 22.000", status: "Entregue" },
  { id: "#NEG-004", date: "01/12/2024", value: "R$ 12.300", status: "Cancelado" },
];

const wishlist = [
  { name: "iPhone 15 Pro Max", price: "R$ 7.499,00", quantity: 50 },
  { name: "Samsung Galaxy S24", price: "R$ 6.199,00", quantity: 30 },
  { name: "MacBook Pro 14\"", price: "R$ 16.499,00", quantity: 10 },
];

export default function RetailerDetails() {
  const { id } = useParams();
  const navigate = useNavigate();

  const getStatusBadge = (status: string) => {
    const styles: Record<string, string> = {
      Entregue: "bg-success/10 text-success",
      "Em Transporte": "bg-primary/10 text-primary",
      Cancelado: "bg-destructive/10 text-destructive",
      "Pronto para Recolha": "bg-warning/10 text-warning",
    };
    return styles[status] || "bg-muted text-muted-foreground";
  };

  return (
    <DashboardLayout>
      <div className="space-y-6">
        {/* Back Button */}
        <Button
          variant="ghost"
          className="gap-2"
          onClick={() => navigate("/admin/lojistas")}
        >
          <ArrowLeft size={18} />
          Voltar para Lojistas
        </Button>

        {/* Header */}
        <div className="bg-card rounded-xl p-6 shadow-card animate-fade-in">
          <div className="flex flex-col md:flex-row md:items-start md:justify-between gap-6">
            <div className="flex items-start gap-4">
              <div className="w-16 h-16 rounded-xl bg-primary/10 flex items-center justify-center">
                <span className="text-2xl font-bold text-primary">
                  {retailerData.name.charAt(0)}
                </span>
              </div>
              <div>
                <div className="flex items-center gap-3">
                  <h1 className="text-2xl font-bold text-card-foreground">
                    {retailerData.name}
                  </h1>
                  <Badge className="bg-success/10 text-success">
                    {retailerData.status}
                  </Badge>
                </div>
                <div className="mt-3 space-y-1.5">
                  <div className="flex items-center gap-2 text-muted-foreground">
                    <Mail size={16} />
                    {retailerData.email}
                  </div>
                  <div className="flex items-center gap-2 text-muted-foreground">
                    <Phone size={16} />
                    {retailerData.phone}
                  </div>
                  <div className="flex items-center gap-2 text-muted-foreground">
                    <MapPin size={16} />
                    {retailerData.address}
                  </div>
                  <div className="flex items-center gap-2 text-muted-foreground">
                    <Calendar size={16} />
                    Cliente desde {retailerData.joinedAt}
                  </div>
                </div>
              </div>
            </div>
            <div className="flex gap-3">
              <Button variant="outline" className="gap-2">
                <Mail size={18} />
                Enviar Email
              </Button>
              <Button className="gap-2">
                <Edit size={18} />
                Editar
              </Button>
            </div>
          </div>

          {/* Stats */}
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-6 pt-6 border-t border-border">
            <div>
              <p className="text-sm text-muted-foreground">Total Negociações</p>
              <p className="text-xl font-bold text-card-foreground">
                {retailerData.totalNegotiations}
              </p>
            </div>
            <div>
              <p className="text-sm text-muted-foreground">Valor Total</p>
              <p className="text-xl font-bold text-primary">
                {retailerData.totalValue}
              </p>
            </div>
            <div>
              <p className="text-sm text-muted-foreground">Ticket Médio</p>
              <p className="text-xl font-bold text-card-foreground">R$ 1.570</p>
            </div>
            <div>
              <p className="text-sm text-muted-foreground">Taxa de Conclusão</p>
              <p className="text-xl font-bold text-success">94.5%</p>
            </div>
          </div>
        </div>

        {/* Tabs */}
        <Tabs defaultValue="orders" className="animate-fade-in">
          <TabsList className="bg-card shadow-card">
            <TabsTrigger value="orders">Histórico de Negociações</TabsTrigger>
            <TabsTrigger value="wishlist">Em Negociação</TabsTrigger>
            <TabsTrigger value="notes">Notas Internas</TabsTrigger>
          </TabsList>

          <TabsContent value="orders" className="mt-4">
            <div className="bg-card rounded-xl shadow-card overflow-hidden">
              <table className="w-full">
                <thead className="bg-muted/50">
                  <tr>
                    <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                      Pedido
                    </th>
                    <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                      Data
                    </th>
                    <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                      Valor
                    </th>
                    <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                      Status
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map((order) => (
                    <tr
                      key={order.id}
                      className="border-t border-border hover:bg-muted/30 transition-colors"
                    >
                      <td className="py-4 px-4 font-medium text-primary">
                        {order.id}
                      </td>
                      <td className="py-4 px-4 text-muted-foreground">
                        {order.date}
                      </td>
                      <td className="py-4 px-4 font-medium text-card-foreground">
                        {order.value}
                      </td>
                      <td className="py-4 px-4">
                        <Badge className={getStatusBadge(order.status)}>
                          {order.status}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </TabsContent>

          <TabsContent value="wishlist" className="mt-4">
            <div className="bg-card rounded-xl shadow-card overflow-hidden">
              <table className="w-full">
                <thead className="bg-muted/50">
                  <tr>
                    <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                      Produto
                    </th>
                    <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                      Preço B2B
                    </th>
                    <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                      Quantidade
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {wishlist.map((item) => (
                    <tr
                      key={item.name}
                      className="border-t border-border hover:bg-muted/30 transition-colors"
                    >
                      <td className="py-4 px-4">
                        <div className="flex items-center gap-3">
                          <div className="w-10 h-10 rounded-lg bg-muted flex items-center justify-center">
                            <Package size={18} className="text-muted-foreground" />
                          </div>
                          <span className="font-medium text-card-foreground">
                            {item.name}
                          </span>
                        </div>
                      </td>
                      <td className="py-4 px-4 font-medium text-primary">
                        {item.price}
                      </td>
                      <td className="py-4 px-4 text-muted-foreground">
                        {item.quantity} unidades
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </TabsContent>

          <TabsContent value="notes" className="mt-4">
            <div className="bg-card rounded-xl p-6 shadow-card">
              <h3 className="font-semibold text-card-foreground mb-4">
                Notas sobre o parceiro
              </h3>
              <Textarea
                placeholder="Adicione notas internas sobre este lojista..."
                className="min-h-[150px]"
                defaultValue="Cliente preferencial. Boa histórico de pagamentos. Preferência por produtos Apple e Samsung. Negociação de exclusividade em andamento para linha de smartphones."
              />
              <Button className="mt-4">Salvar Notas</Button>
            </div>
          </TabsContent>
        </Tabs>
      </div>
    </DashboardLayout>
  );
}
