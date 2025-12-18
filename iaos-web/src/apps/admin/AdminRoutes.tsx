import { Routes, Route } from "react-router-dom";
import Index from "@/pages/Index";
import Products from "@/pages/Products";
import Retailers from "@/pages/Retailers";
import RetailerDetails from "@/pages/RetailerDetails";
import Negotiations from "@/pages/Negotiations";
import NegotiationDetails from "@/pages/NegotiationDetails";
import Chat from "@/pages/Chat";
import Trends from "@/pages/Trends";
import AdminNotFound from "@/apps/admin/AdminNotFound";
import AdminLogin from "@/apps/admin/pages/AdminLogin";
import AdminGuard from "@/auth/AdminGuard";
import RequireRole from "@/auth/RequireRole";
import AdminSettings from "@/apps/admin/pages/AdminSettings";
import AdminFinance from "@/apps/admin/pages/AdminFinance";
import AdminReports from "@/apps/admin/pages/AdminReports";

export default function AdminRoutes() {
  return (
    <Routes>
      <Route path="login" element={<AdminLogin />} />
      <Route element={<AdminGuard />}>
        <Route
          index
          element={
            <RequireRole allowed={["admin", "staff", "finance"]}>
              <Index />
            </RequireRole>
          }
        />
        <Route
          path="produtos"
          element={
            <RequireRole allowed={["admin", "staff"]}>
              <Products />
            </RequireRole>
          }
        />
        <Route
          path="lojistas"
          element={
            <RequireRole allowed={["admin", "staff"]}>
              <Retailers />
            </RequireRole>
          }
        />
        <Route
          path="lojistas/:id"
          element={
            <RequireRole allowed={["admin", "staff"]}>
              <RetailerDetails />
            </RequireRole>
          }
        />
        <Route
          path="negociacoes"
          element={
            <RequireRole allowed={["admin", "staff"]}>
              <Negotiations />
            </RequireRole>
          }
        />
        <Route
          path="negociacoes/:id"
          element={
            <RequireRole allowed={["admin", "staff"]}>
              <NegotiationDetails />
            </RequireRole>
          }
        />
        <Route
          path="chat"
          element={
            <RequireRole allowed={["admin", "staff"]}>
              <Chat />
            </RequireRole>
          }
        />
        <Route
          path="tendencias"
          element={
            <RequireRole allowed={["admin", "staff"]}>
              <Trends />
            </RequireRole>
          }
        />

        <Route
          path="financeiro"
          element={
            <RequireRole allowed={["admin", "finance"]}>
              <AdminFinance />
            </RequireRole>
          }
        />
        <Route
          path="relatorios"
          element={
            <RequireRole allowed={["admin", "finance"]}>
              <AdminReports />
            </RequireRole>
          }
        />
        <Route
          path="configuracoes"
          element={
            <RequireRole allowed={["admin"]}>
              <AdminSettings />
            </RequireRole>
          }
        />

        <Route path="*" element={<AdminNotFound />} />
      </Route>
    </Routes>
  );
}
