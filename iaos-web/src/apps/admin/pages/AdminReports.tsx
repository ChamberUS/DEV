import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

export default function AdminReports() {
  return (
    <DashboardLayout>
      <div className="space-y-6">
        <div>
          <h1 className="text-2xl font-bold text-foreground">Relatórios</h1>
          <p className="text-muted-foreground">Relatórios e exportações (placeholder).</p>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>Em breve</CardTitle>
            <CardDescription>Relatórios serão adicionados aqui.</CardDescription>
          </CardHeader>
          <CardContent className="text-sm text-muted-foreground">
            Esta página existe para RBAC v1: acesso para roles <code>admin</code> e <code>finance</code>.
          </CardContent>
        </Card>
      </div>
    </DashboardLayout>
  );
}

