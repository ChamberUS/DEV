import { ReactNode } from "react";
import { AppSidebar } from "./AppSidebar";
import { AppHeader } from "./AppHeader";

interface DashboardLayoutProps {
  children: ReactNode;
}

export function DashboardLayout({ children }: DashboardLayoutProps) {
  return (
    <div className="min-h-screen bg-background">
      <AppSidebar />
      <div className="pl-[70px] lg:pl-[260px] transition-all duration-300">
        <AppHeader />
        <main className="p-6">{children}</main>
      </div>
    </div>
  );
}
