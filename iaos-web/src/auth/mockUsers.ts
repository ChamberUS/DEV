import type { UserType } from "./userAuth";
import { USER_SESSION_STORAGE_KEY } from "./userAuth";

const MOCK_USERS_STORAGE_KEY = "aios_mock_users";

type StoredUser = { email: string; type?: UserType; password?: string };

const DEFAULT_USERS: StoredUser[] = [];

function safeLocalStorageGet(key: string): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(key);
}

function safeLocalStorageSet(key: string, value: string) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(key, value);
}

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

function isStoredUser(value: unknown): value is StoredUser {
  if (!value || typeof value !== "object") return false;
  const maybe = value as Record<string, unknown>;
  const type = maybe.type;
  const password = maybe.password;
  return (
    typeof maybe.email === "string" &&
    maybe.email.trim().length > 0 &&
    (type === undefined || type === "personal" || type === "business") &&
    (password === undefined || typeof password === "string")
  );
}

function getRegisteredUsers(): StoredUser[] {
  const raw = safeLocalStorageGet(MOCK_USERS_STORAGE_KEY);
  if (!raw) return [];

  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    const users: StoredUser[] = [];
    for (const item of parsed) {
      if (typeof item === "string") {
        const email = normalizeEmail(item);
        if (email) users.push({ email });
        continue;
      }
      if (isStoredUser(item)) {
        users.push({
          email: normalizeEmail(item.email),
          type: item.type,
          password: item.password,
        });
      }
    }
    return users;
  } catch {
    return [];
  }
}

function findUserByEmail(email: string): StoredUser | null {
  const normalized = normalizeEmail(email);
  if (!normalized) return null;

  const defaultUser = DEFAULT_USERS.find((u) => normalizeEmail(u.email) === normalized);
  if (defaultUser) return { email: normalizeEmail(defaultUser.email), type: defaultUser.type, password: defaultUser.password };

  const found = getRegisteredUsers().find((u) => u.email === normalized);
  return found ? { email: found.email, type: found.type, password: found.password } : null;
}

export function isKnownUserEmail(email: string): boolean {
  return Boolean(findUserByEmail(email));
}

export function getKnownUserByEmail(email: string): StoredUser | null {
  const user = findUserByEmail(email);
  if (!user) return null;
  return { email: user.email, type: user.type };
}

export function registerMockUser(input: { email: string; password: string; type?: UserType }): StoredUser {
  const normalizedEmail = normalizeEmail(input.email);
  if (!normalizedEmail) throw new Error("E-mail inválido.");
  if (!input.password) throw new Error("Senha inválida.");

  if (findUserByEmail(normalizedEmail)) {
    throw new Error("Este e-mail já está cadastrado. Faça login.");
  }

  const current = getRegisteredUsers();
  const next = new Map<string, StoredUser>();
  for (const u of current) next.set(u.email, u);

  const user: StoredUser = { email: normalizedEmail, type: input.type, password: input.password };
  next.set(normalizedEmail, user);
  safeLocalStorageSet(MOCK_USERS_STORAGE_KEY, JSON.stringify(Array.from(next.values())));
  return { email: user.email, type: user.type };
}

export function loginMockUser(input: { email: string; password: string }): StoredUser {
  const normalizedEmail = normalizeEmail(input.email);
  if (!normalizedEmail) throw new Error("E-mail inválido.");
  if (!input.password) throw new Error("Senha inválida.");

  const user = findUserByEmail(normalizedEmail);
  if (!user) throw new Error("Usuário não encontrado. Crie uma conta.");

  if (!user.password) throw new Error("Conta antiga sem senha. Refaça o cadastro.");
  if (user.password !== input.password) throw new Error("Senha inválida.");

  return { email: user.email, type: user.type };
}

export function clearMockUsers(): void {
  if (typeof window === "undefined") return;
  window.localStorage.removeItem(MOCK_USERS_STORAGE_KEY);
  window.localStorage.removeItem(USER_SESSION_STORAGE_KEY);
}
