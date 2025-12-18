import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from "recharts";

const data = [
  { month: "Jan", fechados: 45000, cancelados: 3200 },
  { month: "Fev", fechados: 52000, cancelados: 2800 },
  { month: "Mar", fechados: 48000, cancelados: 4100 },
  { month: "Abr", fechados: 61000, cancelados: 3500 },
  { month: "Mai", fechados: 55000, cancelados: 2900 },
  { month: "Jun", fechados: 67000, cancelados: 3800 },
  { month: "Jul", fechados: 72000, cancelados: 2600 },
  { month: "Ago", fechados: 69000, cancelados: 3100 },
];

export function TransactionsChart() {
  return (
    <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
      <h3 className="text-lg font-semibold text-card-foreground mb-4">
        Fluxo de Transações
      </h3>
      <div className="h-[300px]">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} barGap={8}>
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
            <Legend />
            <Bar
              dataKey="fechados"
              name="Pedidos Fechados"
              fill="hsl(var(--primary))"
              radius={[4, 4, 0, 0]}
            />
            <Bar
              dataKey="cancelados"
              name="Cancelados"
              fill="hsl(var(--destructive))"
              radius={[4, 4, 0, 0]}
            />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
