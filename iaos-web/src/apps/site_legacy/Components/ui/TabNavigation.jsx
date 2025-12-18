import React from 'react';
import { motion } from 'framer-motion';
import { cn } from "@/lib/utils";

export default function TabNavigation({ tabs, activeTab, onTabChange, className }) {
  return (
    <div className={cn("border-b border-[#1a4d2e]/30 mb-6", className)}>
      <div className="flex gap-1 overflow-x-auto pb-px scrollbar-hide">
        {tabs.map((tab) => {
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => onTabChange(tab.id)}
              className={cn(
                "relative px-4 py-3 text-sm font-medium whitespace-nowrap transition-colors",
                isActive
                  ? "text-[#1a4d2e]"
                  : "text-white/50 hover:text-white/70"
              )}
            >
              {tab.icon && (
                <tab.icon className="w-4 h-4 inline-block mr-2" />
              )}
              {tab.label}
              {isActive && (
                <motion.div
                  layoutId="activeTab"
                  className="absolute bottom-0 left-0 right-0 h-0.5 bg-gradient-to-r from-[#4a9eff] to-[#1a4d2e]"
                  transition={{ type: "spring", stiffness: 380, damping: 30 }}
                />
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}