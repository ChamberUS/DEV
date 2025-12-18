import type { ReactNode } from "react";
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import {
  ADMIN_SESSION_STORAGE_KEY,
  clearSession as clearAdminStorage,
  getSession as readAdminSession,
  isSessionValid as isAdminSessionValid,
  type AdminSession,
} from "@/auth/adminAuth";
import {
  USER_SESSION_STORAGE_KEY,
  clearUserSession as clearUserStorage,
  getUserSession as readUserSession,
  isUserSessionValid as isUserSessionValidFn,
  type UserSession,
} from "@/auth/userAuth";

type AuthContextValue = {
  adminSession: AdminSession | null;
  userSession: UserSession | null;
  refreshSessions: () => void;
  setUserSession: (session: UserSession) => void;
  clearUser: () => void;
  setAdminSession: (session: AdminSession) => void;
  clearAdmin: () => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);

function safeLocalStorageSet(key: string, value: string) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(key, value);
}

function readValidAdminSession(): AdminSession | null {
  const session = readAdminSession();
  if (isAdminSessionValid(session)) return session;
  if (session) clearAdminStorage();
  return null;
}

function readValidUserSession(): UserSession | null {
  const session = readUserSession();
  if (isUserSessionValidFn(session)) return session;
  if (session) clearUserStorage();
  return null;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [adminSession, setAdminSessionState] = useState<AdminSession | null>(() => readValidAdminSession());
  const [userSession, setUserSessionState] = useState<UserSession | null>(() => readValidUserSession());

  const refreshSessions = useCallback(() => {
    setAdminSessionState(readValidAdminSession());
    setUserSessionState(readValidUserSession());
  }, []);

  const setUserSession = useCallback((session: UserSession) => {
    safeLocalStorageSet(USER_SESSION_STORAGE_KEY, JSON.stringify(session));
    setUserSessionState(session);
  }, []);

  const clearUser = useCallback(() => {
    clearUserStorage();
    setUserSessionState(null);
  }, []);

  const setAdminSession = useCallback((session: AdminSession) => {
    safeLocalStorageSet(ADMIN_SESSION_STORAGE_KEY, JSON.stringify(session));
    setAdminSessionState(session);
  }, []);

  const clearAdmin = useCallback(() => {
    clearAdminStorage();
    setAdminSessionState(null);
  }, []);

  useEffect(() => {
    refreshSessions();

    const onStorage = (e: StorageEvent) => {
      if (e.key === ADMIN_SESSION_STORAGE_KEY || e.key === USER_SESSION_STORAGE_KEY) {
        refreshSessions();
      }
    };

    window.addEventListener("storage", onStorage);
    window.addEventListener("focus", refreshSessions);
    const intervalId = window.setInterval(refreshSessions, 10_000);

    return () => {
      window.removeEventListener("storage", onStorage);
      window.removeEventListener("focus", refreshSessions);
      window.clearInterval(intervalId);
    };
  }, [refreshSessions]);

  const value = useMemo<AuthContextValue>(
    () => ({
      adminSession,
      userSession,
      refreshSessions,
      setUserSession,
      clearUser,
      setAdminSession,
      clearAdmin,
    }),
    [adminSession, clearAdmin, clearUser, refreshSessions, setAdminSession, setUserSession, userSession],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}

