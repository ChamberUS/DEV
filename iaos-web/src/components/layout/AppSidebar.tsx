import { useState, type ComponentType } from "react";
import { NavLink, useLocation, useNavigate } from "react-router-dom";
import {
  LayoutDashboard,
  Package,
  Users,
  ShoppingCart,
  MessageSquare,
  TrendingUp,
  DollarSign,
  FileText,
  ChevronLeft,
  ChevronRight,
  Settings,
  LogOut,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { clearSession, getSession, isSessionValid } from "@/auth/adminAuth";
import { roleHasPermission, type Permission } from "@/auth/permissions";

const menuItems: Array<{
  title: string;
  icon: ComponentType<{ size?: string | number; className?: string }>;
  path: string;
  permission: Permission;
}> = [
  {
    title: "Dashboard",
    icon: LayoutDashboard,
    path: "/admin",
    permission: "dashboard",
  },
  {
    title: "Produtos",
    icon: Package,
    path: "/admin/produtos",
    permission: "products",
  },
  {
    title: "Lojistas",
    icon: Users,
    path: "/admin/lojistas",
    permission: "retailers",
  },
  {
    title: "Negociações",
    icon: ShoppingCart,
    path: "/admin/negociacoes",
    permission: "negotiations",
  },
  {
    title: "Financeiro",
    icon: DollarSign,
    path: "/admin/financeiro",
    permission: "finance",
  },
  {
    title: "Relatórios",
    icon: FileText,
    path: "/admin/relatorios",
    permission: "reports",
  },
  {
    title: "Tendências",
    icon: TrendingUp,
    path: "/admin/tendencias",
    permission: "trends",
  },
  {
    title: "Chat",
    icon: MessageSquare,
    path: "/admin/chat",
    permission: "chat",
  },
];

const bottomMenuItems: Array<{
  title: string;
  icon: ComponentType<{ size?: string | number; className?: string }>;
  path: string;
  permission: Permission;
}> = [
  {
    title: "Configurações",
    icon: Settings,
    path: "/admin/configuracoes",
    permission: "settings",
  },
];

export function AppSidebar() {
  const [collapsed, setCollapsed] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();
  const session = getSession();
  const sessionEmail = isSessionValid(session) ? session.email : null;
  const sessionRole = isSessionValid(session) ? session.role : null;
  const visibleMenuItems = sessionRole
    ? menuItems.filter((item) => roleHasPermission(sessionRole, item.permission))
    : menuItems;
  const visibleBottomMenuItems = sessionRole
    ? bottomMenuItems.filter((item) => roleHasPermission(sessionRole, item.permission))
    : bottomMenuItems;

  return (
    <aside
      className={cn(
        "fixed left-0 top-0 z-40 h-screen bg-sidebar transition-all duration-300 flex flex-col",
        collapsed ? "w-[70px]" : "w-[260px]"
      )}
    >
      {/* Logo */}
      <div className="flex h-16 items-center justify-between px-4 border-b border-sidebar-border">
        {!collapsed && (
          <span className="text-xl font-bold text-sidebar-foreground animate-fade-in">
            IAOS
          </span>
        )}
        <button
          onClick={() => setCollapsed(!collapsed)}
          className="p-2 rounded-lg hover:bg-sidebar-accent text-sidebar-muted hover:text-sidebar-foreground transition-colors"
        >
          {collapsed ? <ChevronRight size={20} /> : <ChevronLeft size={20} />}
        </button>
      </div>

      {/* Navigation */}
      <nav className="flex-1 py-4 px-3 overflow-y-auto">
        <ul className="space-y-1">
          {visibleMenuItems.map((item) => {
            const isActive =
              location.pathname === item.path ||
              location.pathname.startsWith(item.path + "/");
            return (
              <li key={item.path}>
                <NavLink
                  to={item.path}
                  className={cn(
                    "flex items-center gap-3 px-3 py-2.5 rounded-lg transition-all duration-200",
                    isActive
                      ? "bg-sidebar-primary text-sidebar-primary-foreground"
                      : "text-sidebar-muted hover:bg-sidebar-accent hover:text-sidebar-foreground"
                  )}
                >
                  <item.icon size={20} className="flex-shrink-0" />
                  {!collapsed && (
                    <span className="font-medium text-sm animate-fade-in">
                      {item.title}
                    </span>
                  )}
                </NavLink>
              </li>
            );
          })}
        </ul>
      </nav>

      {/* Bottom Menu */}
      <div className="py-4 px-3 border-t border-sidebar-border">
        <ul className="space-y-1">
          {visibleBottomMenuItems.map((item) => {
            const isActive =
              location.pathname === item.path ||
              location.pathname.startsWith(item.path + "/");
            return (
              <li key={item.path}>
                <NavLink
                  to={item.path}
                  className={cn(
                    "flex items-center gap-3 px-3 py-2.5 rounded-lg transition-all duration-200",
                    isActive
                      ? "bg-sidebar-primary text-sidebar-primary-foreground"
                      : "text-sidebar-muted hover:bg-sidebar-accent hover:text-sidebar-foreground"
                  )}
                >
                  <item.icon size={20} className="flex-shrink-0" />
                  {!collapsed && (
                    <span className="font-medium text-sm">{item.title}</span>
                  )}
                </NavLink>
              </li>
            );
          })}
          <li>
            <button
              type="button"
              onClick={() => {
                clearSession();
                navigate("/admin/login", { replace: true });
              }}
              className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sidebar-muted hover:bg-destructive/10 hover:text-destructive transition-all duration-200"
            >
              <LogOut size={20} className="flex-shrink-0" />
              {!collapsed && (
                <span className="font-medium text-sm">Sair</span>
              )}
            </button>
          </li>
        </ul>
        {!collapsed && sessionEmail && (
          <div className="mt-3 px-3 text-xs text-sidebar-muted truncate" title={sessionEmail}>
            {sessionEmail}
          </div>
        )}
      </div>
    </aside>
  );
}
