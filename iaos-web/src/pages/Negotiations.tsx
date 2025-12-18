import { useState } from "react";
import { Search, Filter, Eye, MoreHorizontal, RefreshCw } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

const negotiations = [
  {
    id: "#NEG-2024-001",
    retailer: "Magazine Luiza",
    paymentStatus: "Pago",
    fulfillmentStatus: "Entregue",
    deliveryType: "Expresso",
    total: "R$ 45.890,00",
    date: "10/12/2024",
  },
  {
    id: "#NEG-2024-002",
    retailer: "Americanas",
    paymentStatus: "Pendente",
    fulfillmentStatus: "Em Processamento",
    deliveryType: "Standard",
    total: "R$ 23.450,00",
    date: "09/12/2024",
  },
  {
    id: "#NEG-2024-003",
    retailer: "Amazon Brasil",
    paymentStatus: "Pago",
    fulfillmentStatus: "Em Transporte",
    deliveryType: "Expresso",
    total: "R$ 67.230,00",
    date: "08/12/2024",
  },
  {
    id: "#NEG-2024-004",
    retailer: "Casas Bahia",
    paymentStatus: "Reembolsado",
    fulfillmentStatus: "Cancelado",
    deliveryType: "Standard",
    total: "R$ 12.100,00",
    date: "07/12/2024",
  },
  {
    id: "#NEG-2024-005",
    retailer: "Mercado Livre",
    paymentStatus: "Pago",
    fulfillmentStatus: "Pronto para Recolha",
    deliveryType: "Retirada",
    total: "R$ 34.560,00",
    date: "06/12/2024",
  },
  {
    id: "#NEG-2024-006",
    retailer: "Magazine Luiza",
    paymentStatus: "Pago",
    fulfillmentStatus: "Entregue",
    deliveryType: "Expresso",
    total: "R$ 89.120,00",
    date: "05/12/2024",
  },
];

export default function Negotiations() {
  const [searchTerm, setSearchTerm] = useState("");
  const navigate = useNavigate();

  const getPaymentBadge = (status: string) => {
    const styles: Record<string, string> = {
      Pago: "bg-success/10 text-success",
      Pendente: "bg-warning/10 text-warning",
      Reembolsado: "bg-destructive/10 text-destructive",
    };
    return styles[status] || "bg-muted text-muted-foreground";
  };

  const getFulfillmentBadge = (status: string) => {
    const styles: Record<string, string> = {
      Entregue: "bg-success/10 text-success",
      "Em Transporte": "bg-primary/10 text-primary",
      "Em Processamento": "bg-accent/10 text-accent",
      "Pronto para Recolha": "bg-warning/10 text-warning",
      Cancelado: "bg-destructive/10 text-destructive",
    };
    return styles[status] || "bg-muted text-muted-foreground";
  };

  return (
    <DashboardLayout>
      <div className="space-y-6">
        {/* Page Header */}
        <div>
          <h1 className="text-2xl font-bold text-foreground">Negociações</h1>
          <p className="text-muted-foreground">
            Acompanhe todas as transações e pedidos
          </p>
        </div>

        {/* Filters */}
        <div className="bg-card rounded-xl p-4 shadow-card">
          <div className="flex flex-col sm:flex-row gap-4">
            <div className="relative flex-1">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" size={18} />
              <Input
                placeholder="Pesquisar por número ou lojista..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="pl-10"
              />
            </div>
            <Select>
              <SelectTrigger className="w-full sm:w-[180px]">
                <SelectValue placeholder="Status Pagamento" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">Todos</SelectItem>
                <SelectItem value="paid">Pago</SelectItem>
                <SelectItem value="pending">Pendente</SelectItem>
                <SelectItem value="refunded">Reembolsado</SelectItem>
              </SelectContent>
            </Select>
            <Select>
              <SelectTrigger className="w-full sm:w-[180px]">
                <SelectValue placeholder="Status Entrega" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">Todos</SelectItem>
                <SelectItem value="delivered">Entregue</SelectItem>
                <SelectItem value="transit">Em Transporte</SelectItem>
                <SelectItem value="processing">Em Processamento</SelectItem>
                <SelectItem value="cancelled">Cancelado</SelectItem>
              </SelectContent>
            </Select>
            <Button variant="outline" className="gap-2">
              <Filter size={18} />
              Mais Filtros
            </Button>
          </div>
        </div>

        {/* Negotiations Table */}
        <div className="bg-card rounded-xl shadow-card overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-muted/50">
                <tr>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Nº Pedido
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Lojista
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Pagamento
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Status
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Entrega
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Total
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Data
                  </th>
                  <th className="text-right py-4 px-4 text-sm font-medium text-muted-foreground">
                    Ações
                  </th>
                </tr>
              </thead>
              <tbody>
                {negotiations.map((neg, index) => (
                  <tr
                    key={neg.id}
                    className="border-t border-border hover:bg-muted/30 transition-colors cursor-pointer animate-fade-in"
                    style={{ animationDelay: `${index * 50}ms` }}
                    onClick={() => navigate(`/negociacoes/${neg.id}`)}
                  >
                    <td className="py-4 px-4 font-medium text-primary">
                      {neg.id}
                    </td>
                    <td className="py-4 px-4">
                      <div className="flex items-center gap-2">
                        <div className="w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center">
                          <span className="text-xs font-bold text-primary">
                            {neg.retailer.charAt(0)}
                          </span>
                        </div>
                        <span className="text-card-foreground">{neg.retailer}</span>
                      </div>
                    </td>
                    <td className="py-4 px-4">
                      <Badge className={getPaymentBadge(neg.paymentStatus)}>
                        {neg.paymentStatus}
                      </Badge>
                    </td>
                    <td className="py-4 px-4">
                      <Badge className={getFulfillmentBadge(neg.fulfillmentStatus)}>
                        {neg.fulfillmentStatus}
                      </Badge>
                    </td>
                    <td className="py-4 px-4 text-muted-foreground">
                      {neg.deliveryType}
                    </td>
                    <td className="py-4 px-4 font-semibold text-card-foreground">
                      {neg.total}
                    </td>
                    <td className="py-4 px-4 text-muted-foreground">
                      {neg.date}
                    </td>
                    <td className="py-4 px-4 text-right" onClick={(e) => e.stopPropagation()}>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon">
                            <MoreHorizontal size={18} />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem
                            className="gap-2"
                            onClick={() => navigate(`/negociacoes/${neg.id}`)}
                          >
                            <Eye size={16} />
                            Ver Detalhes
                          </DropdownMenuItem>
                          <DropdownMenuItem className="gap-2">
                            <RefreshCw size={16} />
                            Processar Reembolso
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </DashboardLayout>
  );
}
