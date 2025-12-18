import { DashboardLayout } from "@/components/layout/DashboardLayout";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
} from "recharts";
import { TrendingUp, TrendingDown, Package, Users } from "lucide-react";
import { cn } from "@/lib/utils";

const forecastData = [
  { month: "Jan", atual: 45000, previsao: 48000 },
  { month: "Fev", atual: 52000, previsao: 55000 },
  { month: "Mar", atual: 48000, previsao: 51000 },
  { month: "Abr", atual: 61000, previsao: 65000 },
  { month: "Mai", atual: 55000, previsao: 60000 },
  { month: "Jun", atual: 67000, previsao: 72000 },
  { month: "Jul", atual: null, previsao: 78000 },
  { month: "Ago", atual: null, previsao: 82000 },
  { month: "Set", atual: null, previsao: 85000 },
];

const categoryData = [
  { name: "Eletrônicos", value: 45, growth: 12.5 },
  { name: "Computadores", value: 25, growth: 8.2 },
  { name: "Games", value: 15, growth: 22.1 },
  { name: "TVs", value: 10, growth: -3.4 },
  { name: "Outros", value: 5, growth: 5.7 },
];

const COLORS = [
  "hsl(var(--primary))",
  "hsl(var(--accent))",
  "hsl(var(--success))",
  "hsl(var(--warning))",
  "hsl(var(--muted-foreground))",
];

const marketNews = [
  {
    title: "Magazine Luiza expande operações no Nordeste",
    source: "E-commerce Brasil",
    time: "Há 2 horas",
    impact: "positive",
  },
  {
    title: "Black Friday 2024 quebra recordes de vendas online",
    source: "Valor Econômico",
    time: "Há 5 horas",
    impact: "positive",
  },
  {
    title: "Novo marco regulatório para e-commerce em discussão",
    source: "Folha de S.Paulo",
    time: "Há 1 dia",
    impact: "neutral",
  },
  {
    title: "Americanas anuncia reestruturação de dívidas",
    source: "InfoMoney",
    time: "Há 2 dias",
    impact: "negative",
  },
];

export default function Trends() {
  return (
    <DashboardLayout>
      <div className="space-y-6">
        {/* Page Header */}
        <div>
          <h1 className="text-2xl font-bold text-foreground">
            Análise de Tendências
          </h1>
          <p className="text-muted-foreground">
            Insights e previsões do mercado B2B
          </p>
        </div>

        {/* Charts Row */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Forecast Chart */}
          <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
            <h3 className="text-lg font-semibold text-card-foreground mb-4">
              Previsão de Vendas
            </h3>
            <div className="h-[300px]">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={forecastData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
                  <XAxis
                    dataKey="month"
                    axisLine={false}
                    tickLine={false}
                    tick={{ fill: "hsl(var(--muted-foreground))", fontSize: 12 }}
                  />
                  <YAxis
                    axisLine={false}
                    tickLine={false}
                    tick={{ fill: "hsl(var(--muted-foreground))", fontSize: 12 }}
                    tickFormatter={(value) => `R$${value / 1000}k`}
                  />
                  <Tooltip
                    contentStyle={{
                      backgroundColor: "hsl(var(--card))",
                      border: "1px solid hsl(var(--border))",
                      borderRadius: "8px",
                    }}
                    formatter={(value: number) =>
                      new Intl.NumberFormat("pt-BR", {
                        style: "currency",
                        currency: "BRL",
                      }).format(value)
                    }
                  />
                  <Line
                    type="monotone"
                    dataKey="atual"
                    stroke="hsl(var(--primary))"
                    strokeWidth={2}
                    dot={{ fill: "hsl(var(--primary))", strokeWidth: 2 }}
                    name="Vendas Atuais"
                  />
                  <Line
                    type="monotone"
                    dataKey="previsao"
                    stroke="hsl(var(--accent))"
                    strokeWidth={2}
                    strokeDasharray="5 5"
                    dot={{ fill: "hsl(var(--accent))", strokeWidth: 2 }}
                    name="Previsão"
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </div>

          {/* Category Distribution */}
          <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
            <h3 className="text-lg font-semibold text-card-foreground mb-4">
              Tendência por Categoria
            </h3>
            <div className="flex items-center gap-6">
              <div className="w-[200px] h-[200px]">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie
                      data={categoryData}
                      cx="50%"
                      cy="50%"
                      innerRadius={60}
                      outerRadius={80}
                      paddingAngle={5}
                      dataKey="value"
                    >
                      {categoryData.map((_, index) => (
                        <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                      ))}
                    </Pie>
                    <Tooltip
                      contentStyle={{
                        backgroundColor: "hsl(var(--card))",
                        border: "1px solid hsl(var(--border))",
                        borderRadius: "8px",
                      }}
                      formatter={(value: number) => `${value}%`}
                    />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <div className="flex-1 space-y-3">
                {categoryData.map((category, index) => (
                  <div key={category.name} className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <div
                        className="w-3 h-3 rounded-full"
                        style={{ backgroundColor: COLORS[index] }}
                      />
                      <span className="text-sm text-card-foreground">
                        {category.name}
                      </span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-card-foreground">
                        {category.value}%
                      </span>
                      <span
                        className={cn(
                          "text-xs flex items-center gap-0.5",
                          category.growth > 0 ? "text-success" : "text-destructive"
                        )}
                      >
                        {category.growth > 0 ? (
                          <TrendingUp size={12} />
                        ) : (
                          <TrendingDown size={12} />
                        )}
                        {Math.abs(category.growth)}%
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>

        {/* Stats Cards */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-xl gradient-primary flex items-center justify-center">
                <TrendingUp size={24} className="text-primary-foreground" />
              </div>
              <div>
                <p className="text-sm text-muted-foreground">Crescimento Previsto</p>
                <p className="text-xl font-bold text-card-foreground">+18.5%</p>
              </div>
            </div>
          </div>
          <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-xl gradient-accent flex items-center justify-center">
                <Package size={24} className="text-accent-foreground" />
              </div>
              <div>
                <p className="text-sm text-muted-foreground">Categorias em Alta</p>
                <p className="text-xl font-bold text-card-foreground">Games, Tech</p>
              </div>
            </div>
          </div>
          <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-xl gradient-success flex items-center justify-center">
                <Users size={24} className="text-success-foreground" />
              </div>
              <div>
                <p className="text-sm text-muted-foreground">Novos Parceiros (previsão)</p>
                <p className="text-xl font-bold text-card-foreground">+45</p>
              </div>
            </div>
          </div>
          <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-xl bg-warning flex items-center justify-center">
                <TrendingDown size={24} className="text-warning-foreground" />
              </div>
              <div>
                <p className="text-sm text-muted-foreground">Risco de Queda</p>
                <p className="text-xl font-bold text-card-foreground">TVs (-3.4%)</p>
              </div>
            </div>
          </div>
        </div>

        {/* Market News */}
        <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
          <h3 className="text-lg font-semibold text-card-foreground mb-4">
            Notícias do Mercado
          </h3>
          <div className="space-y-4">
            {marketNews.map((news, index) => (
              <div
                key={index}
                className="flex items-start gap-4 p-4 rounded-lg hover:bg-muted/50 transition-colors"
              >
                <div
                  className={cn(
                    "w-2 h-2 rounded-full mt-2",
                    news.impact === "positive" && "bg-success",
                    news.impact === "negative" && "bg-destructive",
                    news.impact === "neutral" && "bg-muted-foreground"
                  )}
                />
                <div className="flex-1">
                  <h4 className="font-medium text-card-foreground">
                    {news.title}
                  </h4>
                  <div className="flex items-center gap-2 mt-1">
                    <span className="text-sm text-muted-foreground">
                      {news.source}
                    </span>
                    <span className="text-xs text-muted-foreground">•</span>
                    <span className="text-sm text-muted-foreground">
                      {news.time}
                    </span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </DashboardLayout>
  );
}
