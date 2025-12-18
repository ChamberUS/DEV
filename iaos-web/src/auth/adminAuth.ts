import type { Role } from "./permissions";
import { getAdminUserByEmail } from "./adminUsers";

export type AdminSession = {
  email: string;
  role: Role;
  exp: number; // epoch ms
};

export const ADMIN_SESSION_STORAGE_KEY = "aios_admin_session";

const EIGHT_HOURS_MS = 8 * 60 * 60 * 1000;
const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;

function safeLocalStorageGet(key: string): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(key);
}

function safeLocalStorageSet(key: string, value: string) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(key, value);
}

function safeLocalStorageRemove(key: string) {
  if (typeof window === "undefined") return;
  window.localStorage.removeItem(key);
}

function isAdminSession(value: unknown): value is AdminSession {
  if (!value || typeof value !== "object") return false;
  const maybe = value as Record<string, unknown>;
  const role = maybe.role;
  const isRole = role === "admin" || role === "staff" || role === "finance";
  return (
    typeof maybe.email === "string" &&
    maybe.email.length > 0 &&
    isRole &&
    typeof maybe.exp === "number" &&
    Number.isFinite(maybe.exp)
  );
}

export function getSession(): AdminSession | null {
  const raw = safeLocalStorageGet(ADMIN_SESSION_STORAGE_KEY);
  if (!raw) return null;

  try {
    const parsed: unknown = JSON.parse(raw);
    if (!isAdminSession(parsed)) {
      clearSession();
      return null;
    }
    return parsed;
  } catch {
    clearSession();
    return null;
  }
}

export function isSessionValid(session: AdminSession | null = getSession()): session is AdminSession {
  if (!session) return false;
  if (!isAdminSession(session)) return false;
  return session.exp > Date.now();
}

export function setSession(options: { email: string; role: Role; rememberMe: boolean }): AdminSession {
  const now = Date.now();
  const exp = now + (options.rememberMe ? SEVEN_DAYS_MS : EIGHT_HOURS_MS);

  const session: AdminSession = {
    email: options.email.trim(),
    role: options.role,
    exp,
  };

  safeLocalStorageSet(ADMIN_SESSION_STORAGE_KEY, JSON.stringify(session));
  return session;
}

export function clearSession(): void {
  safeLocalStorageRemove(ADMIN_SESSION_STORAGE_KEY);
}

export function getAdminPassword(): string | null {
  const password = import.meta.env.VITE_ADMIN_PASSWORD;
  if (typeof password !== "string") return null;
  if (password.length === 0) return null;
  return password;
}

export function authenticateAdminLogin(input: {
  email: string;
  password: string;
}): { email: string; role: Role } | null {
  const user = getAdminUserByEmail(input.email);
  if (!user) return null;

  const expectedPassword = getAdminPassword();
  if (!expectedPassword) return null;

  if (input.password !== expectedPassword) return null;
  return user;
}
