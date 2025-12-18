import { useState } from "react";
import { Plus, Search, Filter, MoreHorizontal, Edit, Trash2 } from "lucide-react";
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

const products = [
  {
    id: 1,
    name: "iPhone 15 Pro Max",
    price: "R$ 8.999,00",
    b2bPrice: "R$ 7.499,00",
    category: "Eletrônicos",
    retailer: "Magazine Luiza",
    status: "Publicado",
    stock: 145,
    image: "https://images.unsplash.com/photo-1695048133142-1a20484d2569?w=100&h=100&fit=crop",
  },
  {
    id: 2,
    name: "Samsung Galaxy S24 Ultra",
    price: "R$ 7.499,00",
    b2bPrice: "R$ 6.199,00",
    category: "Eletrônicos",
    retailer: "Americanas",
    status: "Publicado",
    stock: 89,
    image: "https://images.unsplash.com/photo-1610945415295-d9bbf067e59c?w=100&h=100&fit=crop",
  },
  {
    id: 3,
    name: "MacBook Pro 14\"",
    price: "R$ 18.999,00",
    b2bPrice: "R$ 16.499,00",
    category: "Computadores",
    retailer: "Amazon Brasil",
    status: "Rascunho",
    stock: 34,
    image: "https://images.unsplash.com/photo-1517336714731-489689fd1ca8?w=100&h=100&fit=crop",
  },
  {
    id: 4,
    name: "PlayStation 5",
    price: "R$ 4.499,00",
    b2bPrice: "R$ 3.899,00",
    category: "Games",
    retailer: "Casas Bahia",
    status: "Publicado",
    stock: 56,
    image: "https://images.unsplash.com/photo-1606813907291-d86efa9b94db?w=100&h=100&fit=crop",
  },
  {
    id: 5,
    name: "Smart TV LG 55\" OLED",
    price: "R$ 5.999,00",
    b2bPrice: "R$ 4.999,00",
    category: "TVs",
    retailer: "Mercado Livre",
    status: "Publicado",
    stock: 23,
    image: "https://images.unsplash.com/photo-1593359677879-a4bb92f829d1?w=100&h=100&fit=crop",
  },
];

export default function Products() {
  const [searchTerm, setSearchTerm] = useState("");

  return (
    <DashboardLayout>
      <div className="space-y-6">
        {/* Page Header */}
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-foreground">Produtos</h1>
            <p className="text-muted-foreground">
              Gerencie o catálogo de produtos dos lojistas
            </p>
          </div>
          <Button className="gap-2">
            <Plus size={18} />
            Adicionar Produto
          </Button>
        </div>

        {/* Filters */}
        <div className="bg-card rounded-xl p-4 shadow-card">
          <div className="flex flex-col sm:flex-row gap-4">
            <div className="relative flex-1">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" size={18} />
              <Input
                placeholder="Pesquisar produtos..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="pl-10"
              />
            </div>
            <Select>
              <SelectTrigger className="w-full sm:w-[180px]">
                <SelectValue placeholder="Categoria" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">Todas</SelectItem>
                <SelectItem value="electronics">Eletrônicos</SelectItem>
                <SelectItem value="computers">Computadores</SelectItem>
                <SelectItem value="games">Games</SelectItem>
                <SelectItem value="tvs">TVs</SelectItem>
              </SelectContent>
            </Select>
            <Select>
              <SelectTrigger className="w-full sm:w-[180px]">
                <SelectValue placeholder="Lojista" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">Todos</SelectItem>
                <SelectItem value="magalu">Magazine Luiza</SelectItem>
                <SelectItem value="americanas">Americanas</SelectItem>
                <SelectItem value="amazon">Amazon Brasil</SelectItem>
              </SelectContent>
            </Select>
            <Button variant="outline" className="gap-2">
              <Filter size={18} />
              Filtros
            </Button>
          </div>
        </div>

        {/* Products Table */}
        <div className="bg-card rounded-xl shadow-card overflow-hidden">
          <div className="overflow-x-auto">
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
                    Categoria
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Lojista
                  </th>
                  <th className="text-left py-4 px-4 text-sm font-medium text-muted-foreground">
                    Stock
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
                {products.map((product, index) => (
                  <tr
                    key={product.id}
                    className="border-t border-border hover:bg-muted/30 transition-colors animate-fade-in"
                    style={{ animationDelay: `${index * 50}ms` }}
                  >
                    <td className="py-4 px-4">
                      <div className="flex items-center gap-3">
                        <img
                          src={product.image}
                          alt={product.name}
                          className="w-12 h-12 rounded-lg object-cover"
                        />
                        <div>
                          <p className="font-medium text-card-foreground">
                            {product.name}
                          </p>
                          <p className="text-sm text-muted-foreground">
                            {product.price}
                          </p>
                        </div>
                      </div>
                    </td>
                    <td className="py-4 px-4 font-medium text-primary">
                      {product.b2bPrice}
                    </td>
                    <td className="py-4 px-4 text-muted-foreground">
                      {product.category}
                    </td>
                    <td className="py-4 px-4 text-muted-foreground">
                      {product.retailer}
                    </td>
                    <td className="py-4 px-4 text-muted-foreground">
                      {product.stock} un.
                    </td>
                    <td className="py-4 px-4">
                      <Badge
                        variant={product.status === "Publicado" ? "default" : "secondary"}
                        className={
                          product.status === "Publicado"
                            ? "bg-success/10 text-success hover:bg-success/20"
                            : ""
                        }
                      >
                        {product.status}
                      </Badge>
                    </td>
                    <td className="py-4 px-4 text-right">
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon">
                            <MoreHorizontal size={18} />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem className="gap-2">
                            <Edit size={16} />
                            Editar
                          </DropdownMenuItem>
                          <DropdownMenuItem className="gap-2 text-destructive">
                            <Trash2 size={16} />
                            Excluir
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
