import { useMemo } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ChevronDown, LayoutDashboard, LogOut } from "lucide-react";
import { cn } from "@/lib/utils";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { useAuth } from "@/auth/AuthContext";

type TriggerVariant = "legacyHeader" | "inline";
type MenuVariant = "default" | "legacy";

type UserMenuProps = {
  label?: string;
  triggerVariant?: TriggerVariant;
  variant?: MenuVariant;
  contentClassName?: string;
  onLoggedOut?: () => void;
};

export default function UserMenu({
  label = "Admin",
  triggerVariant = "legacyHeader",
  variant = "default",
  contentClassName,
  onLoggedOut,
}: UserMenuProps) {
  const navigate = useNavigate();
  const { adminSession, clearAdmin } = useAuth();
  const session = adminSession;

  const display = useMemo(() => {
    if (!session) return null;
    const initial = session.email?.charAt(0)?.toUpperCase() || "A";
    return {
      initial,
      email: session.email,
      role: session.role,
    };
  }, [session]);

  if (!display) return null;

  const isLegacy = variant === "legacy";

  const triggerClassName =
    triggerVariant === "inline"
      ? cn(
          "inline-flex items-center gap-2 transition-colors",
          isLegacy
            ? "text-emerald-400 hover:text-emerald-300"
            : "text-primary hover:text-primary/90"
        )
      : cn(
          "flex items-center gap-2 px-3 py-2 rounded-lg transition-colors",
          isLegacy ? "hover:bg-[#1a4d2e]/30" : "hover:bg-accent"
        );

  const avatarClassName =
    triggerVariant === "inline"
      ? "w-7 h-7 rounded-full bg-gradient-to-br from-[#4a9eff] to-[#1a4d2e] flex items-center justify-center text-white text-xs font-bold"
      : "w-8 h-8 rounded-full bg-gradient-to-br from-[#4a9eff] to-[#1a4d2e] flex items-center justify-center text-white text-sm font-bold";

  const labelClassName = cn(
    "text-sm block",
    isLegacy
      ? triggerVariant === "inline"
        ? "text-emerald-400"
        : "text-white"
      : "text-foreground"
  );
  const emailClassName = cn(
    "text-xs block",
    isLegacy
      ? triggerVariant === "inline"
        ? "text-emerald-400/80"
        : "text-white/60"
      : "text-muted-foreground"
  );
  const chevronClassName = cn(
    "w-4 h-4",
    isLegacy
      ? triggerVariant === "inline"
        ? "text-emerald-400/70"
        : "text-white/40"
      : "text-muted-foreground"
  );
  const contentBaseClassName = cn("w-56", isLegacy && "bg-[#0a0a0a] border-[#1a4d2e]/50");
  const itemClassName = cn(isLegacy && "text-white/70 hover:text-white hover:bg-[#1a4d2e]/30");
  const logoutItemClassName = cn(
    isLegacy ? "text-red-400 hover:text-red-300 hover:bg-red-500/10" : "text-destructive focus:text-destructive"
  );

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button type="button" className={triggerClassName}>
          <div className={avatarClassName}>
            {display.initial}
          </div>
          <div className="text-left">
            <span className={labelClassName}>{label}</span>
            <span className={emailClassName}>{display.email}</span>
          </div>
          <ChevronDown className={chevronClassName} />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className={cn(contentBaseClassName, contentClassName)}>
        <DropdownMenuItem asChild className={itemClassName}>
          <Link to="/admin" className="flex items-center">
            <LayoutDashboard className="w-4 h-4 mr-2" />
            Dashboard
          </Link>
        </DropdownMenuItem>
        <DropdownMenuSeparator className={cn(isLegacy && "bg-[#1a4d2e]/30")} />
        <DropdownMenuItem
          className={logoutItemClassName}
          onSelect={(e) => {
            e.preventDefault();
            clearAdmin();
            onLoggedOut?.();
            navigate("/", { replace: true });
          }}
        >
          <LogOut className="w-4 h-4 mr-2" />
          Logout
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
