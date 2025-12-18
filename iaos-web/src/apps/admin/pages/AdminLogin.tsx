import type { FormEvent } from "react";
import { useMemo, useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Checkbox } from "@/components/ui/checkbox";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { authenticateAdminLogin, getAdminPassword, getSession, isSessionValid, setSession } from "@/auth/adminAuth";

type LocationState = {
  from?: {
    pathname?: string;
    search?: string;
    hash?: string;
  };
};

function buildRedirectTarget(from: LocationState["from"]): string {
  const pathname = typeof from?.pathname === "string" ? from.pathname : "";
  if (!pathname.startsWith("/admin") || pathname === "/admin/login") return "/admin";

  const search = typeof from?.search === "string" ? from.search : "";
  const hash = typeof from?.hash === "string" ? from.hash : "";
  return `${pathname}${search}${hash}`;
}

export default function AdminLogin() {
  const navigate = useNavigate();
  const location = useLocation();

  const redirectTo = useMemo(() => {
    const state = location.state as LocationState | null;
    return buildRedirectTarget(state?.from);
  }, [location.state]);

  const passwordConfigured = Boolean(getAdminPassword());
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [rememberMe, setRememberMe] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const existingSession = getSession();
  if (isSessionValid(existingSession)) {
    return <Navigate to={redirectTo} replace />;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    const normalizedEmail = email.trim();
    if (!normalizedEmail || !password) {
      setError("Preencha e-mail e senha.");
      return;
    }

    if (!passwordConfigured) {
      setError("Senha de admin não configurada. Defina VITE_ADMIN_PASSWORD no .env.");
      return;
    }

    setSubmitting(true);
    try {
      const result = authenticateAdminLogin({ email: normalizedEmail, password });
      if (!result) {
        setError("E-mail não autorizado ou senha inválida.");
        return;
      }

      setSession({ email: result.email, role: result.role, rememberMe });
      navigate(redirectTo, { replace: true });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="min-h-screen bg-muted flex items-center justify-center p-6">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle className="text-2xl">Admin</CardTitle>
          <CardDescription>Acesse o painel administrativo do IAOS.</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-4">
            {error && (
              <Alert variant="destructive">
                <AlertTitle>Não foi possível entrar</AlertTitle>
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}

            <div className="space-y-2">
              <Label htmlFor="email">E-mail</Label>
              <Input
                id="email"
                type="email"
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="admin@exemplo.com"
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="password">Senha</Label>
              <Input
                id="password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
              />
            </div>

            <div className="flex items-center justify-between">
              <label className="flex items-center gap-2 text-sm text-muted-foreground" htmlFor="rememberMe">
                <Checkbox
                  id="rememberMe"
                  checked={rememberMe}
                  onCheckedChange={(checked) => setRememberMe(checked === true)}
                />
                Lembrar-me (7 dias)
              </label>
              <span className="text-xs text-muted-foreground">Sem lembrar: 8 horas</span>
            </div>

            <Button type="submit" className="w-full" disabled={submitting}>
              {submitting ? "Entrando..." : "Entrar"}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
