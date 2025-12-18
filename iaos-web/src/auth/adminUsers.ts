import type { Role } from "./permissions";

export const ADMIN_USERS: Array<{ email: string; role: Role }> = [
  { email: "Buynnex@buynnex.com", role: "admin" },
  { email: "staff@aios.com", role: "staff" },
  { email: "finance@aios.com", role: "finance" },
];

export function getAdminUserByEmail(email: string): { email: string; role: Role } | null {
  const normalized = email.trim().toLowerCase();
  const found = ADMIN_USERS.find((u) => u.email.toLowerCase() === normalized);
  return found ? { email: found.email, role: found.role } : null;
}

