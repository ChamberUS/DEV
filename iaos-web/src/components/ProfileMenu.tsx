import { useMemo } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ChevronDown, LogOut, User } from "lucide-react";
import { cn } from "@/lib/utils";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { useAuth } from "@/auth/AuthContext";

type ProfileMenuProps = {
  label?: string;
  variant?: "default" | "legacy";
  onLoggedOut?: () => void;
};

export default function ProfileMenu({ label = "Perfil", variant = "default", onLoggedOut }: ProfileMenuProps) {
  const navigate = useNavigate();
  const { userSession, clearUser } = useAuth();
  const session = userSession;
  const display = useMemo(() => {
    if (!session) return null;
    return { email: session.email };
  }, [session]);

  if (!display) return null;

  const isLegacy = variant === "legacy";

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          className={cn(
            "flex items-center gap-2 px-3 py-2 rounded-lg transition-colors",
            isLegacy ? "hover:bg-[#1a4d2e]/30" : "hover:bg-accent"
          )}
        >
          <div className="w-8 h-8 rounded-full bg-gradient-to-br from-emerald-500 to-cyan-500 flex items-center justify-center text-white text-sm font-bold">
            <User className="w-4 h-4" />
          </div>
          <div className="text-left">
            <span className={cn("text-sm block", isLegacy ? "text-white" : "text-foreground")}>{label}</span>
            <span className={cn("text-xs block", isLegacy ? "text-white/60" : "text-muted-foreground")}>
              {display.email}
            </span>
          </div>
          <ChevronDown className={cn("w-4 h-4", isLegacy ? "text-white/40" : "text-muted-foreground")} />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className={cn("w-56", isLegacy && "bg-[#0a0a0a] border-[#1a4d2e]/50")}>
        <DropdownMenuItem asChild className={cn(isLegacy && "text-white/70 hover:text-white hover:bg-[#1a4d2e]/30")}>
          <Link to="/account" className="flex items-center">
            <User className="w-4 h-4 mr-2" />
            Minha conta
          </Link>
        </DropdownMenuItem>
        <DropdownMenuSeparator className={cn(isLegacy && "bg-[#1a4d2e]/30")} />
        <DropdownMenuItem
          className={cn(
            isLegacy
              ? "text-red-400 hover:text-red-300 hover:bg-red-500/10"
              : "text-destructive focus:text-destructive"
          )}
          onSelect={(e) => {
            e.preventDefault();
            clearUser();
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
