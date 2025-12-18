export interface Product {
  id: string;
  name: string;
  description: string;
  sku: string;
  category: string;
  partner: string;
  partnerRating: number;
  partnerSales: number;
  price: number;
  originalPrice?: number;
  currency: string;
  minOrder: number;
  stock: number;
  stockStatus: 'available' | 'low' | 'out';
  image: string;
  images: string[];
  condition: 'new' | 'refurbished' | 'used';
  warranty: string;
  leadTime: string;
  viewCount: number;
  inCarts: number;
  soldCount: number;
  specs: ProductSpec[];
  volumePricing: VolumePricing[];
  tags: string[];
  certifications: string[];
  createdAt: string;
  updatedAt: string;
}

export interface ProductSpec {
  label: string;
  value: string;
}

export interface VolumePricing {
  minQty: number;
  maxQty: number | null;
  price: number;
  discount: number;
}

export interface FilterOption {
  id: string;
  label: string;
  count?: number;
}

export interface CategoryFilter {
  id: string;
  label: string;
  type: 'checkbox' | 'range' | 'color' | 'size';
  options?: FilterOption[];
  min?: number;
  max?: number;
}

export interface CategoryFilters {
  [key: string]: CategoryFilter[];
}
