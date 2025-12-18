import { DollarSign, TrendingUp, ShoppingCart, Users } from "lucide-react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { KPICard } from "@/components/dashboard/KPICard";
import { TransactionsChart } from "@/components/dashboard/TransactionsChart";
import { TopRetailersTable } from "@/components/dashboard/TopRetailersTable";

export default function Dashboard() {
  return (
    <DashboardLayout>
      <div className="space-y-6">
        {/* Page Header */}
        <div>
          <h1 className="text-2xl font-bold text-foreground">Dashboard</h1>
          <p className="text-muted-foreground">
            Visão geral das negociações e performance
          </p>
        </div>

        {/* KPI Cards */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <KPICard
            title="Valor Total Negociado"
            value="R$ 1.245.890"
            change="+12.5% vs mês anterior"
            changeType="positive"
            icon={DollarSign}
            iconColor="gradient-primary"
          />
          <KPICard
            title="Crescimento Realizado"
            value="+R$ 156.230"
            change="+8.2% este mês"
            changeType="positive"
            icon={TrendingUp}
            iconColor="gradient-success"
          />
          <KPICard
            title="Pedidos Totais"
            value="1.456"
            change="89 novos hoje"
            changeType="neutral"
            icon={ShoppingCart}
            iconColor="gradient-accent"
          />
          <KPICard
            title="Lojistas Ativos"
            value="234"
            change="+15 este mês"
            changeType="positive"
            icon={Users}
            iconColor="bg-warning"
          />
        </div>

        {/* Charts and Tables */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <TransactionsChart />
          <TopRetailersTable />
        </div>
      </div>
    </DashboardLayout>
  );
}
