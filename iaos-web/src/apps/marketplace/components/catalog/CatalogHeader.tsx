import { Search, ShoppingCart, Heart, User, Menu, Bell } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';

interface CatalogHeaderProps {
  searchQuery: string;
  onSearchChange: (value: string) => void;
  onSearch: () => void;
}

export const CatalogHeader = ({ searchQuery, onSearchChange, onSearch }: CatalogHeaderProps) => {
  return (
    <header className="bg-card border-b border-border sticky top-0 z-50 shadow-sm">
      {/* Top bar */}
      <div className="bg-primary/5 border-b border-border">
        <div className="container mx-auto px-4 py-2">
          <div className="flex items-center justify-between text-sm">
            <div className="flex items-center gap-4 text-muted-foreground">
              <span>Plataforma B2B</span>
              <span className="hidden md:inline">|</span>
              <span className="hidden md:inline">Preços exclusivos para empresas</span>
            </div>
            <div className="flex items-center gap-4 text-muted-foreground">
              <a href="#" className="hover:text-primary transition-colors">Ajuda</a>
              <a href="#" className="hover:text-primary transition-colors">Contato</a>
            </div>
          </div>
        </div>
      </div>

      {/* Main header */}
      <div className="container mx-auto px-4 py-4">
        <div className="flex items-center gap-6">
          {/* Logo */}
          <a href="/marketplace" className="flex items-center gap-2 flex-shrink-0">
            <div className="w-10 h-10 rounded-lg bg-primary flex items-center justify-center">
              <span className="text-primary-foreground font-bold text-xl">I</span>
            </div>
            <div className="hidden sm:block">
              <span className="font-bold text-xl text-foreground">IAOS</span>
              <span className="text-xs text-muted-foreground block -mt-1">Product Navigator</span>
            </div>
          </a>

          {/* Search */}
          <div className="flex-1 max-w-2xl">
            <div className="flex">
              <Input
                type="text"
                placeholder="Buscar produtos, marcas, categorias..."
                value={searchQuery}
                onChange={(e) => onSearchChange(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && onSearch()}
                className="rounded-r-none border-r-0 h-11 focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-primary"
              />
              <Button
                onClick={onSearch}
                className="rounded-l-none h-11 px-6 bg-primary hover:bg-primary/90"
              >
                <Search className="h-5 w-5" />
              </Button>
            </div>
          </div>

          {/* Actions */}
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="icon" className="relative">
              <Bell className="h-5 w-5 text-muted-foreground" />
              <Badge className="absolute -top-1 -right-1 h-5 w-5 flex items-center justify-center p-0 text-xs bg-urgency">
                3
              </Badge>
            </Button>
            <Button variant="ghost" size="icon" className="relative">
              <Heart className="h-5 w-5 text-muted-foreground" />
              <Badge className="absolute -top-1 -right-1 h-5 w-5 flex items-center justify-center p-0 text-xs bg-popularity">
                12
              </Badge>
            </Button>
            <Button variant="ghost" size="icon" className="relative">
              <ShoppingCart className="h-5 w-5 text-muted-foreground" />
              <Badge className="absolute -top-1 -right-1 h-5 w-5 flex items-center justify-center p-0 text-xs bg-accent">
                5
              </Badge>
            </Button>
            <Button variant="outline" size="sm" className="hidden md:flex gap-2 ml-2">
              <User className="h-4 w-4" />
              Minha Conta
            </Button>
          </div>
        </div>
      </div>
    </header>
  );
};
