import { ReactNode, useEffect, useState } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { clearSession, getSession, isSessionValid } from "./adminAuth";

type AdminGuardProps = {
  children?: ReactNode;
};

export default function AdminGuard({ children }: AdminGuardProps) {
  const location = useLocation();
  const [, forceRender] = useState(0);
  const session = getSession();
  const valid = isSessionValid(session);

  useEffect(() => {
    if (!session) return;
    if (!isSessionValid(session)) return;

    const delayMs = Math.max(0, session.exp - Date.now()) + 50;
    const timeoutId = window.setTimeout(() => forceRender((v) => v + 1), delayMs);
    return () => window.clearTimeout(timeoutId);
  }, [session?.exp]);

  if (!valid) {
    if (session) clearSession();
    return <Navigate to="/admin/login" state={{ from: location }} replace />;
  }

  if (children) return <>{children}</>;
  return <Outlet />;
}
