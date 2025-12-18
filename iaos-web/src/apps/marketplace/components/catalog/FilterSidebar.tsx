import { useState } from 'react';
import { ChevronDown, ChevronUp, X, Filter } from 'lucide-react';
import { Checkbox } from '@/components/ui/checkbox';
import { Button } from '@/components/ui/button';
import { Slider } from '@/components/ui/slider';
import { Badge } from '@/components/ui/badge';
import { categories, partners, categoryFilters } from '@/apps/marketplace/data/mockData';

interface FilterSidebarProps {
  selectedCategory: string | null;
  selectedPartners: string[];
  stockStatus: string[];
  priceRange: [number, number];
  dynamicFilters: Record<string, string[]>;
  onCategoryChange: (category: string | null) => void;
  onPartnersChange: (partners: string[]) => void;
  onStockStatusChange: (status: string[]) => void;
  onPriceRangeChange: (range: [number, number]) => void;
  onDynamicFilterChange: (filterId: string, values: string[]) => void;
  onClearAll: () => void;
  activeFiltersCount: number;
}

export const FilterSidebar = ({
  selectedCategory,
  selectedPartners,
  stockStatus,
  priceRange,
  dynamicFilters,
  onCategoryChange,
  onPartnersChange,
  onStockStatusChange,
  onPriceRangeChange,
  onDynamicFilterChange,
  onClearAll,
  activeFiltersCount,
}: FilterSidebarProps) => {
  const [expandedSections, setExpandedSections] = useState<Set<string>>(
    new Set(['categories', 'partners', 'stock', 'price'])
  );

  const toggleSection = (section: string) => {
    const newExpanded = new Set(expandedSections);
    if (newExpanded.has(section)) {
      newExpanded.delete(section);
    } else {
      newExpanded.add(section);
    }
    setExpandedSections(newExpanded);
  };

  const currentCategoryFilters = selectedCategory ? categoryFilters[selectedCategory] || [] : [];

  const stockOptions = [
    { id: 'available', label: 'Em estoque', count: 4532 },
    { id: 'low', label: 'Últimas unidades', count: 234 },
    { id: 'out', label: 'Esgotado', count: 89 },
  ];

  return (
    <aside className="w-full lg:w-72 flex-shrink-0">
      <div className="bg-card border border-border rounded-xl p-5 sticky top-24">
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-2">
            <Filter className="h-5 w-5 text-primary" />
            <h2 className="font-semibold text-foreground">Filtros</h2>
            {activeFiltersCount > 0 && (
              <Badge variant="secondary" className="bg-primary/10 text-primary">
                {activeFiltersCount}
              </Badge>
            )}
          </div>
          {activeFiltersCount > 0 && (
            <Button
              variant="ghost"
              size="sm"
              onClick={onClearAll}
              className="text-muted-foreground hover:text-destructive text-xs"
            >
              Limpar
            </Button>
          )}
        </div>

        {/* Categories */}
        <div className="filter-section">
          <button
            onClick={() => toggleSection('categories')}
            className="filter-title w-full"
          >
            <span>Categorias</span>
            {expandedSections.has('categories') ? (
              <ChevronUp className="h-4 w-4 text-muted-foreground" />
            ) : (
              <ChevronDown className="h-4 w-4 text-muted-foreground" />
            )}
          </button>
          {expandedSections.has('categories') && (
            <div className="space-y-2 animate-fade-in">
              {categories.map((cat) => (
                <button
                  key={cat.id}
                  onClick={() => onCategoryChange(selectedCategory === cat.id ? null : cat.id)}
                  className={`w-full flex items-center justify-between py-2 px-3 rounded-lg text-sm transition-colors ${
                    selectedCategory === cat.id
                      ? 'bg-primary/10 text-primary font-medium'
                      : 'hover:bg-muted text-foreground'
                  }`}
                >
                  <span>{cat.label}</span>
                  <span className="text-muted-foreground text-xs">({cat.count})</span>
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Partners */}
        <div className="filter-section">
          <button
            onClick={() => toggleSection('partners')}
            className="filter-title w-full"
          >
            <span>Parceiros</span>
            {expandedSections.has('partners') ? (
              <ChevronUp className="h-4 w-4 text-muted-foreground" />
            ) : (
              <ChevronDown className="h-4 w-4 text-muted-foreground" />
            )}
          </button>
          {expandedSections.has('partners') && (
            <div className="space-y-2 animate-fade-in">
              {partners.map((partner) => (
                <label
                  key={partner.id}
                  className="flex items-center gap-3 py-1.5 cursor-pointer"
                >
                  <Checkbox
                    checked={selectedPartners.includes(partner.id)}
                    onCheckedChange={(checked) => {
                      if (checked) {
                        onPartnersChange([...selectedPartners, partner.id]);
                      } else {
                        onPartnersChange(selectedPartners.filter((p) => p !== partner.id));
                      }
                    }}
                  />
                  <span className="text-sm text-foreground flex-1">{partner.label}</span>
                  <span className="text-xs text-muted-foreground">({partner.count})</span>
                </label>
              ))}
            </div>
          )}
        </div>

        {/* Stock Status */}
        <div className="filter-section">
          <button
            onClick={() => toggleSection('stock')}
            className="filter-title w-full"
          >
            <span>Disponibilidade</span>
            {expandedSections.has('stock') ? (
              <ChevronUp className="h-4 w-4 text-muted-foreground" />
            ) : (
              <ChevronDown className="h-4 w-4 text-muted-foreground" />
            )}
          </button>
          {expandedSections.has('stock') && (
            <div className="space-y-2 animate-fade-in">
              {stockOptions.map((option) => (
                <label
                  key={option.id}
                  className="flex items-center gap-3 py-1.5 cursor-pointer"
                >
                  <Checkbox
                    checked={stockStatus.includes(option.id)}
                    onCheckedChange={(checked) => {
                      if (checked) {
                        onStockStatusChange([...stockStatus, option.id]);
                      } else {
                        onStockStatusChange(stockStatus.filter((s) => s !== option.id));
                      }
                    }}
                  />
                  <span className="text-sm text-foreground flex-1">{option.label}</span>
                  <span className="text-xs text-muted-foreground">({option.count})</span>
                </label>
              ))}
            </div>
          )}
        </div>

        {/* Price Range */}
        <div className="filter-section">
          <button
            onClick={() => toggleSection('price')}
            className="filter-title w-full"
          >
            <span>Faixa de Preço</span>
            {expandedSections.has('price') ? (
              <ChevronUp className="h-4 w-4 text-muted-foreground" />
            ) : (
              <ChevronDown className="h-4 w-4 text-muted-foreground" />
            )}
          </button>
          {expandedSections.has('price') && (
            <div className="space-y-4 animate-fade-in pt-2">
              <Slider
                value={priceRange}
                onValueChange={(value) => onPriceRangeChange(value as [number, number])}
                max={50000}
                min={0}
                step={500}
                className="w-full"
              />
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">
                  R$ {priceRange[0].toLocaleString('pt-BR')}
                </span>
                <span className="text-muted-foreground">
                  R$ {priceRange[1].toLocaleString('pt-BR')}
                </span>
              </div>
            </div>
          )}
        </div>

        {/* Dynamic Category Filters */}
        {currentCategoryFilters.map((filter) => (
          <div key={filter.id} className="filter-section">
            <button
              onClick={() => toggleSection(filter.id)}
              className="filter-title w-full"
            >
              <span>{filter.label}</span>
              {expandedSections.has(filter.id) ? (
                <ChevronUp className="h-4 w-4 text-muted-foreground" />
              ) : (
                <ChevronDown className="h-4 w-4 text-muted-foreground" />
              )}
            </button>
            {expandedSections.has(filter.id) && filter.options && (
              <div className="space-y-2 animate-fade-in">
                {filter.options.map((option) => (
                  <label
                    key={option.id}
                    className="flex items-center gap-3 py-1.5 cursor-pointer"
                  >
                    <Checkbox
                      checked={(dynamicFilters[filter.id] || []).includes(option.id)}
                      onCheckedChange={(checked) => {
                        const currentValues = dynamicFilters[filter.id] || [];
                        if (checked) {
                          onDynamicFilterChange(filter.id, [...currentValues, option.id]);
                        } else {
                          onDynamicFilterChange(
                            filter.id,
                            currentValues.filter((v) => v !== option.id)
                          );
                        }
                      }}
                    />
                    <span className="text-sm text-foreground flex-1">{option.label}</span>
                    {option.count && (
                      <span className="text-xs text-muted-foreground">({option.count})</span>
                    )}
                  </label>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </aside>
  );
};
