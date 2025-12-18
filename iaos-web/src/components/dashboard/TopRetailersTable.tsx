import { TrendingUp, TrendingDown } from "lucide-react";
import { cn } from "@/lib/utils";

const retailers = [
  {
    name: "Magazine Luiza",
    volume: "R$ 245.000",
    transactions: 156,
    growth: 12.5,
    status: "up",
  },
  {
    name: "Americanas",
    volume: "R$ 198.000",
    transactions: 134,
    growth: 8.2,
    status: "up",
  },
  {
    name: "Casas Bahia",
    volume: "R$ 167.000",
    transactions: 98,
    growth: -3.4,
    status: "down",
  },
  {
    name: "Amazon Brasil",
    volume: "R$ 145.000",
    transactions: 87,
    growth: 15.7,
    status: "up",
  },
  {
    name: "Mercado Livre",
    volume: "R$ 132.000",
    transactions: 76,
    growth: 5.1,
    status: "up",
  },
];

export function TopRetailersTable() {
  return (
    <div className="bg-card rounded-xl p-5 shadow-card animate-fade-in">
      <h3 className="text-lg font-semibold text-card-foreground mb-4">
        Lojistas de Destaque
      </h3>
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="border-b border-border">
              <th className="text-left py-3 px-2 text-sm font-medium text-muted-foreground">
                Lojista
              </th>
              <th className="text-right py-3 px-2 text-sm font-medium text-muted-foreground">
                Volume
              </th>
              <th className="text-right py-3 px-2 text-sm font-medium text-muted-foreground">
                Transações
              </th>
              <th className="text-right py-3 px-2 text-sm font-medium text-muted-foreground">
                Crescimento
              </th>
            </tr>
          </thead>
          <tbody>
            {retailers.map((retailer, index) => (
              <tr
                key={retailer.name}
                className="border-b border-border/50 last:border-0 hover:bg-muted/30 transition-colors"
                style={{ animationDelay: `${index * 50}ms` }}
              >
                <td className="py-3 px-2">
                  <div className="flex items-center gap-3">
                    <div className="w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center">
                      <span className="text-xs font-bold text-primary">
                        {retailer.name.charAt(0)}
                      </span>
                    </div>
                    <span className="font-medium text-card-foreground">
                      {retailer.name}
                    </span>
                  </div>
                </td>
                <td className="py-3 px-2 text-right font-medium text-card-foreground">
                  {retailer.volume}
                </td>
                <td className="py-3 px-2 text-right text-muted-foreground">
                  {retailer.transactions}
                </td>
                <td className="py-3 px-2 text-right">
                  <div
                    className={cn(
                      "inline-flex items-center gap-1 px-2 py-1 rounded-full text-xs font-medium",
                      retailer.status === "up"
                        ? "bg-success/10 text-success"
                        : "bg-destructive/10 text-destructive"
                    )}
                  >
                    {retailer.status === "up" ? (
                      <TrendingUp size={12} />
                    ) : (
                      <TrendingDown size={12} />
                    )}
                    {Math.abs(retailer.growth)}%
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
