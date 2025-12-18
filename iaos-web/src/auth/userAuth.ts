export type UserType = "personal" | "business";

export type UserSession = {
  email: string;
  type: UserType;
  exp: number; // epoch ms
};

export const USER_SESSION_STORAGE_KEY = "aios_user_session";

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

function isUserType(value: unknown): value is UserType {
  return value === "personal" || value === "business";
}

function isUserSession(value: unknown): value is UserSession {
  if (!value || typeof value !== "object") return false;
  const maybe = value as Record<string, unknown>;
  return (
    typeof maybe.email === "string" &&
    maybe.email.length > 0 &&
    isUserType(maybe.type) &&
    typeof maybe.exp === "number" &&
    Number.isFinite(maybe.exp)
  );
}

export function getUserSession(): UserSession | null {
  const raw = safeLocalStorageGet(USER_SESSION_STORAGE_KEY);
  if (!raw) return null;

  try {
    const parsed: unknown = JSON.parse(raw);
    if (!isUserSession(parsed)) {
      clearUserSession();
      return null;
    }
    return parsed;
  } catch {
    clearUserSession();
    return null;
  }
}

export function isUserSessionValid(session: UserSession | null = getUserSession()): session is UserSession {
  if (!session) return false;
  if (!isUserSession(session)) return false;
  return session.exp > Date.now();
}

export function setUserSession(options: {
  email: string;
  type: UserType;
  rememberMe: boolean;
}): UserSession {
  const now = Date.now();
  const exp = now + (options.rememberMe ? SEVEN_DAYS_MS : EIGHT_HOURS_MS);

  const session: UserSession = {
    email: options.email.trim(),
    type: options.type,
    exp,
  };

  safeLocalStorageSet(USER_SESSION_STORAGE_KEY, JSON.stringify(session));
  return session;
}

export function clearUserSession(): void {
  safeLocalStorageRemove(USER_SESSION_STORAGE_KEY);
}

