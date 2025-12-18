import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { createPageUrl } from '@/utils/siteLegacy';
import { motion } from 'framer-motion';
import { 
  Store, 
  ShoppingBag, 
  Menu,
  X,
  ChevronDown,
  MessageCircle,
  TrendingUp,
  Wallet,
  ArrowLeftRight,
  Activity
} from 'lucide-react';
import { Button } from "@/components/ui/button";
import SearchBar from '@/apps/site_legacy/Components/layout/SearchBar';
import UserMenu from '@/components/UserMenu';
import ProfileMenu from '@/components/ProfileMenu';
import { useAuth } from '@/auth/AuthContext';

const navigationCategories = [
  {
    name: 'Marketplace',
    items: [
      { name: 'Ver Produtos', page: 'Marketplace' },
      { name: 'Lojistas', page: 'Stores' },
      { name: 'Categorias', page: 'Dashboard' },
    ]
  },
  {
    name: 'Minha Loja',
    items: [
      { name: 'Minha Loja', page: 'MyStore' },
      { name: 'Vendas', page: 'SalesCashback' },
      { name: 'Transações', page: 'Transactions' },
    ]
  },
  {
    name: 'Chat',
    items: [
      { name: 'Chat', page: 'Chat' },
      { name: 'Suporte IAOS', page: 'ChatBYX' },
    ]
  },
  {
    name: 'Mais',
    items: [
      { name: 'Trade', page: 'Trade' },
      { name: 'Earn', page: 'Earn' },
      { name: 'Conversor', page: 'Converter' },
    ]
  },
];

export default function Layout({ children, currentPageName }) {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const { adminSession, userSession } = useAuth();
  const hasValidAdminSession = Boolean(adminSession);
  const hasValidUserSession = Boolean(userSession);

  return (
    <div className="min-h-screen bg-[#000000]">
      {/* Top Navigation Bar */}
      <nav className="fixed top-0 left-0 right-0 h-16 bg-[#0a0a0a]/95 backdrop-blur-xl border-b border-[#1a4d2e]/30 z-50">
        <div className="max-w-[1920px] mx-auto h-full px-8 flex items-center justify-between">
          {/* Logo */}
          <Link 
            to={createPageUrl('Dashboard')}
            className="flex items-center gap-2 hover:opacity-80 transition-opacity"
          >
            <div className="text-2xl font-bold">
              <span className="text-[#4a9eff]">A</span>
              <span className="text-[#1a4d2e]">I</span>
              <span className="text-white">OS</span>
            </div>
          </Link>

          {/* Desktop Navigation */}
          <div className="hidden lg:flex items-center gap-1">
            <Button
              asChild
              variant="ghost"
              className="text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 transition-all h-10 px-4"
            >
              <Link to="/marketplace">Marketplace</Link>
            </Button>
            <Button
              asChild
              variant="ghost"
              className="text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 transition-all h-10 px-4"
            >
              <Link to="/wallet" className="inline-flex items-center gap-2">
                <Wallet className="w-4 h-4" />
                Carteira
              </Link>
            </Button>
            <Button
              asChild
              variant="ghost"
              className="text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 transition-all h-10 px-4"
            >
              <Link to="/transactions" className="inline-flex items-center gap-2">
                <ArrowLeftRight className="w-4 h-4" />
                Transações
              </Link>
            </Button>
            <Button
              asChild
              variant="ghost"
              className="text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 transition-all h-10 px-4"
            >
              <Link to="/network" className="inline-flex items-center gap-2">
                <Activity className="w-4 h-4" />
                Status da Rede
              </Link>
            </Button>
            {navigationCategories.map((category) => (
              <div 
                key={category.name}
                className="group relative"
                onMouseEnter={(e) => {
                  const dropdown = e.currentTarget.querySelector('[data-dropdown]');
                  if (dropdown) dropdown.classList.remove('hidden');
                }}
                onMouseLeave={(e) => {
                  const dropdown = e.currentTarget.querySelector('[data-dropdown]');
                  if (dropdown) dropdown.classList.add('hidden');
                }}
              >
                <Button 
                  variant="ghost" 
                  className="text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 transition-all h-10 px-4"
                >
                  {category.name}
                  <ChevronDown className="w-3 h-3 ml-1" />
                </Button>
                <div 
                  data-dropdown
                  className="hidden absolute top-full left-0 mt-1 min-w-[200px] bg-[#0a0a0a] border border-[#1a4d2e]/50 rounded-lg shadow-xl z-50"
                >
                  {category.items.map((item) => (
                    <Link
                      key={item.page}
                      to={createPageUrl(item.page)}
                      className="block px-4 py-2 text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 transition-colors first:rounded-t-lg last:rounded-b-lg"
                    >
                      {item.name}
                    </Link>
                  ))}
                </div>
              </div>
            ))}
          </div>

          {/* Search Bar */}
          <div className="hidden lg:flex flex-1 max-w-xl mx-8">
            <SearchBar />
          </div>

          {/* User Menu */}
          <div className="flex items-center gap-4">
            {!hasValidAdminSession && hasValidUserSession && (
              <Button
                asChild
                className="bg-white/5 text-white/80 hover:bg-white/10 h-10 px-4 hidden md:inline-flex"
              >
                <Link to="/merchant">Lojista</Link>
              </Button>
            )}
            {hasValidAdminSession && (
              <UserMenu
                label="Admin"
                variant="legacy"
                contentClassName="bg-[#0a0a0a] border-[#1a4d2e]/50"
              />
            )}
            {!hasValidAdminSession && hasValidUserSession && (
              <ProfileMenu
                label="Perfil"
                variant="legacy"
              />
            )}
            {!hasValidAdminSession && !hasValidUserSession && (
              <Button
                asChild
                className="bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-medium px-6 h-10"
              >
                <Link to="/auth/login">Entrar</Link>
              </Button>
            )}

            {/* Mobile Menu Button */}
            <Button 
              variant="ghost" 
              size="icon" 
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
              className="lg:hidden text-white"
            >
              {mobileMenuOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
            </Button>
          </div>
        </div>

        {/* Mobile Menu */}
        {mobileMenuOpen && (
          <motion.div
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            className="lg:hidden bg-[#0a0a0a] border-b border-[#1a4d2e]/30"
          >
            <div className="px-4 py-4 space-y-2">
              <div className="space-y-1">
                <p className="text-white/50 text-xs font-semibold px-3 py-2">Acesso rápido</p>
                <Link
                  to="/marketplace"
                  className="block px-3 py-2 text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 rounded-lg transition-colors"
                  onClick={() => setMobileMenuOpen(false)}
                >
                  Marketplace
                </Link>
                {!hasValidAdminSession && hasValidUserSession && (
                  <Link
                    to="/merchant"
                    className="block px-3 py-2 text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 rounded-lg transition-colors"
                    onClick={() => setMobileMenuOpen(false)}
                  >
                    Lojista
                  </Link>
                )}
                <Link
                  to="/wallet"
                  className="block px-3 py-2 text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 rounded-lg transition-colors"
                  onClick={() => setMobileMenuOpen(false)}
                >
                  Carteira
                </Link>
                <Link
                  to="/transactions"
                  className="block px-3 py-2 text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 rounded-lg transition-colors"
                  onClick={() => setMobileMenuOpen(false)}
                >
                  Transações
                </Link>
                <Link
                  to="/network"
                  className="block px-3 py-2 text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 rounded-lg transition-colors"
                  onClick={() => setMobileMenuOpen(false)}
                >
                  Status da Rede
                </Link>
              </div>
              {navigationCategories.map((category) => (
                <div key={category.name} className="space-y-1">
                  <p className="text-white/50 text-xs font-semibold px-3 py-2">{category.name}</p>
                  {category.items.map((item) => (
                    <Link
                      key={item.page}
                      to={createPageUrl(item.page)}
                      className="block px-3 py-2 text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 rounded-lg transition-colors"
                      onClick={() => setMobileMenuOpen(false)}
                    >
                      {item.name}
                    </Link>
                  ))}
                </div>
              ))}
            </div>
          </motion.div>
        )}
      </nav>

      {/* Main Content */}
      <main className="min-h-screen pt-16">
        <div className="relative z-10">
          {children}
        </div>
      </main>
    </div>
  );
}
