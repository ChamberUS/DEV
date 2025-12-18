import { ReactNode, useEffect, useState } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { clearUserSession, getUserSession, isUserSessionValid } from "./userAuth";

type UserGuardProps = {
  children?: ReactNode;
};

export default function UserGuard({ children }: UserGuardProps) {
  const location = useLocation();
  const [, forceRender] = useState(0);
  const session = getUserSession();
  const valid = isUserSessionValid(session);

  useEffect(() => {
    if (!session) return;
    if (!isUserSessionValid(session)) return;

    const delayMs = Math.max(0, session.exp - Date.now()) + 50;
    const timeoutId = window.setTimeout(() => forceRender((v) => v + 1), delayMs);
    return () => window.clearTimeout(timeoutId);
  }, [session?.exp]);

  if (!valid) {
    if (session) clearUserSession();
    return <Navigate to="/auth/login" state={{ returnTo: location.pathname }} replace />;
  }

  if (children) return <>{children}</>;
  return <Outlet />;
}
