export type Role = "admin" | "staff" | "finance";

export type Permission =
  | "dashboard"
  | "products"
  | "retailers"
  | "negotiations"
  | "trends"
  | "chat"
  | "finance"
  | "reports"
  | "settings";

export const ROLE_PERMS: Record<Role, Permission[]> = {
  admin: [
    "dashboard",
    "products",
    "retailers",
    "negotiations",
    "trends",
    "chat",
    "finance",
    "reports",
    "settings",
  ],
  staff: ["dashboard", "products", "retailers", "negotiations", "trends", "chat"],
  finance: ["dashboard", "finance", "reports"],
};

export function roleHasPermission(role: Role, permission: Permission): boolean {
  return ROLE_PERMS[role].includes(permission);
}

