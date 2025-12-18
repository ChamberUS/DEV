import React from 'react';
import { cn } from "@/lib/utils";

export default function GlassCard({ children, className, gradient, ...props }) {
  return (
    <div
      className={cn(
        "rounded-xl border backdrop-blur-xl",
        gradient 
          ? "bg-gradient-to-br from-[#1a4d2e]/20 via-[#4a9eff]/10 to-[#1a4d2e]/20 border-[#1a4d2e]/40"
          : "bg-[#0a0a0a]/60 border-[#1a4d2e]/20",
        "shadow-xl shadow-black/20",
        "transition-all duration-300 hover:shadow-2xl hover:border-[#1a4d2e]/40",
        className
      )}
      {...props}
    >
      {children}
    </div>
  );
}