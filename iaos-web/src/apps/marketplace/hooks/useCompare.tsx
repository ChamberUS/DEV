import { createContext, useContext, useState, ReactNode } from 'react';
import { Product } from '@/apps/marketplace/types/product';

interface CompareContextType {
  products: Product[];
  addProduct: (product: Product) => void;
  removeProduct: (productId: string) => void;
  clearAll: () => void;
  isComparing: (productId: string) => boolean;
  canAdd: boolean;
}

const CompareContext = createContext<CompareContextType | undefined>(undefined);

const MAX_COMPARE = 4;

export const CompareProvider = ({ children }: { children: ReactNode }) => {
  const [products, setProducts] = useState<Product[]>([]);

  const addProduct = (product: Product) => {
    if (products.length < MAX_COMPARE && !products.find((p) => p.id === product.id)) {
      setProducts((prev) => [...prev, product]);
    }
  };

  const removeProduct = (productId: string) => {
    setProducts((prev) => prev.filter((p) => p.id !== productId));
  };

  const clearAll = () => {
    setProducts([]);
  };

  const isComparing = (productId: string) => {
    return products.some((p) => p.id === productId);
  };

  const canAdd = products.length < MAX_COMPARE;

  return (
    <CompareContext.Provider
      value={{ products, addProduct, removeProduct, clearAll, isComparing, canAdd }}
    >
      {children}
    </CompareContext.Provider>
  );
};

export const useCompare = () => {
  const context = useContext(CompareContext);
  if (!context) {
    throw new Error('useCompare must be used within a CompareProvider');
  }
  return context;
};
