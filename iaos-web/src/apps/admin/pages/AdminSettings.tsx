import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

export default function AdminSettings() {
  return (
    <DashboardLayout>
      <div className="space-y-6">
        <div>
          <h1 className="text-2xl font-bold text-foreground">Configurações</h1>
          <p className="text-muted-foreground">Ajustes do painel administrativo (placeholder).</p>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>Em breve</CardTitle>
            <CardDescription>Configurações administrativas serão adicionadas aqui.</CardDescription>
          </CardHeader>
          <CardContent className="text-sm text-muted-foreground">
            Esta página existe para RBAC v1: acesso restrito a role <code>admin</code>.
          </CardContent>
        </Card>
      </div>
    </DashboardLayout>
  );
}

