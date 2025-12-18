import { Routes, Route } from "react-router-dom";
import Index from "./pages/Index";
import ProductDetails from "./pages/ProductDetails";
import Compare from "./pages/Compare";
import Checkout from "./pages/Checkout";
import NotFound from "./pages/NotFound";
import { CartProvider } from "./hooks/useCart";
import { CompareProvider } from "./hooks/useCompare";
import { CompareBar } from "./components/compare/CompareBar";

export default function MarketplaceRoutes() {
  return (
    <CartProvider>
      <CompareProvider>
        <>
          <Routes>
            <Route index element={<Index />} />
            <Route path="product/:id" element={<ProductDetails />} />
            <Route path="compare" element={<Compare />} />
            <Route path="checkout" element={<Checkout />} />
            <Route path="*" element={<NotFound />} />
          </Routes>
          <CompareBar />
        </>
      </CompareProvider>
    </CartProvider>
  );
}

