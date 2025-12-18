import { useState } from "react";
import { Plus, Search, MoreHorizontal, Eye, Edit, Mail } from "lucide-react";
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

const retailers = [
  {
    id: 1,
    name: "Magazine Luiza",
    email: "parceiro@magalu.com.br",
    totalNegotiations: 156,
    totalValue: "R$ 245.000",
    city: "São Paulo, SP",
    lastSeen: "Há 2 horas",
    status: "Ativo",
  },
  {
    id: 2,
    name: "Americanas S.A.",
    email: "b2b@americanas.com",
    totalNegotiations: 134,
    totalValue: "R$ 198.000",
    city: "Rio de Janeiro, RJ",
    lastSeen: "Há 1 dia",
    status: "Ativo",
  },
  {
    id: 3,
    name: "Casas Bahia",
    email: "comercial@casasbahia.com.br",
    totalNegotiations: 98,
    totalValue: "R$ 167.000",
    city: "São Caetano do Sul, SP",
    lastSeen: "Há 3 dias",
    status: "Pendente",
  },
  {
    id: 4,
    name: "Amazon Brasil",
    email: "sellers@amazon.com.br",
    totalNegotiations: 87,
    totalValue: "R$ 145.000",
    city: "São Paulo, SP",
    lastSeen: "Há 5 horas",
    status: "Ativo",
  },
  {
    id: 5,
    name: "Mercado Livre",
    email: "partners@mercadolivre.com",
    totalNegotiations: 76,
    totalValue: "R$ 132.000",
    city: "Osasco, SP",
    lastSeen: "Há 12 horas",
    status: "Ativo",
  },
  {
    id: 6,
    name: "Via Varejo",
    email: "contato@viavarejo.com.br",
    totalNegotiations: 45,
    totalValue: "R$ 89.000",
    city: "São Caetano do Sul, SP",
    lastSeen: "Há 7 dias",
    status: "Inativo",
  },
];

export default function Retailers() {
  const [searchTerm, setSearchTerm] = useState("");
  const navigate = useNavigate();

  const getStatusBadge = (status: string) => {
    const styles = {
      Ativo: "bg-success/10 text-success hover:bg-success/20",
      Pendente: "bg-warning/10 text-warning hover:bg-warning/20",
      Inativo: "bg-muted text-muted-foreground",
    };
    return styles[status as keyof typeof styles] || styles.Inativo;
  };

  return (
    <DashboardLayout>
      <div className="space-y-6">
        {/* Page Header */}
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-foreground">Lojistas</h1>
            <p className="text-muted-foreground">
              Gerencie seus parceiros e relacionamentos B2B
            </p>
          </div>
          <Button className="gap-2">
            <Plus size={18} />
            Adicionar Lojista
          </Button>
        </div>

        {/* Search */}
        <div className="bg-card rounded-xl p-4 shadow-card">
          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" size={18} />
            <Input
              placeholder="Pesquisar lojistas..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="pl-10"
            />
          </div>
        </div>

        {/* Retailers Table */}
        <div className="bg-card rounded-xl shadow-card overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-muted/50">
                <tr>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Lojista
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Negociações
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Valor Total
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Localização
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Última Atividade
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Status
                  </th>
                  <th className="text-right py-4 px-4 text-sm font-medium text-muted-foreground">
                    Ações
                  </th>
                </tr>
              </thead>
              <tbody>
                {retailers.map((retailer, index) => (
                  <tr
                    key={retailer.id}
                    className="border-t border-border hover:bg-muted/30 transition-colors cursor-pointer animate-fade-in"
                    style={{ animationDelay: `${index * 50}ms` }}
                    onClick={() => navigate(`/lojistas/${retailer.id}`)}
                  >
                    <td className="py-4 px-4">
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center">
                          <span className="text-sm font-bold text-primary">
                            {retailer.name.charAt(0)}
                          </span>
                        </div>
                        <div>
                          <p className="font-medium text-card-foreground">
                            {retailer.name}
                          </p>
                          <p className="text-sm text-muted-foreground">
                            {retailer.email}
                          </p>
                        </div>
                      </div>
                    </td>
                    <td className="py-4 px-4 font-medium text-card-foreground">
                      {retailer.totalNegotiations}
                    </td>
                    <td className="py-4 px-4 font-medium text-primary">
                      {retailer.totalValue}
                    </td>
                    <td className="py-4 px-4 text-muted-foreground">
                      {retailer.city}
                    </td>
                    <td className="py-4 px-4 text-muted-foreground">
                      {retailer.lastSeen}
                    </td>
                    <td className="py-4 px-4">
                      <Badge className={getStatusBadge(retailer.status)}>
                        {retailer.status}
                      </Badge>
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
                            onClick={() => navigate(`/lojistas/${retailer.id}`)}
                          >
                            <Eye size={16} />
                            Ver Detalhes
                          </DropdownMenuItem>
                          <DropdownMenuItem className="gap-2">
                            <Edit size={16} />
                            Editar
                          </DropdownMenuItem>
                          <DropdownMenuItem className="gap-2">
                            <Mail size={16} />
                            Enviar Email
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
