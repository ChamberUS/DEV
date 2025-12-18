import type { ReactNode } from "react";
import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { toast } from "@/hooks/use-toast";
import { clearSession, getSession, isSessionValid } from "./adminAuth";
import type { Role } from "./permissions";

export default function RequireRole({
  allowed,
  children,
}: {
  allowed: Role[];
  children: ReactNode;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const session = getSession();
  const sessionEmail = session?.email ?? null;
  const sessionRole = session?.role ?? null;
  const sessionExp = session?.exp ?? null;

  const validSession = isSessionValid(session);
  const allowedByRole = validSession ? allowed.includes(session.role) : false;

  useEffect(() => {
    if (!validSession) {
      if (session) clearSession();
      navigate("/admin/login", { replace: true, state: { from: location } });
      return;
    }

    if (!allowedByRole) {
      toast({
        variant: "destructive",
        title: "Acesso negado",
        description: "Seu usuário não tem permissão para acessar esta página.",
      });
      navigate("/admin", { replace: true });
    }
  }, [allowedByRole, location, navigate, sessionEmail, sessionExp, sessionRole, validSession]);

  if (!validSession) return null;
  if (!allowedByRole) return null;
  return <>{children}</>;
}
