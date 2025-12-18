import { Routes, Route } from "react-router-dom";
import type { ReactNode } from "react";
import Layout from "./Layout.jsx";

import Home from "./Pages/Home.jsx";
import Dashboard from "./Pages/Dashboard.jsx";
import Stores from "./Pages/Stores.jsx";
import SalesCashback from "./Pages/SalesCashback.jsx";
import Transactions from "./Pages/Transactions.jsx";
import Wallet from "./Pages/Wallet.jsx";
import Chat from "./Pages/Chat.jsx";
import AuthLogin from "./Pages/AuthLogin.jsx";
import AuthRegister from "./Pages/AuthRegister.jsx";
import Account from "./Pages/Account.jsx";
import UserGuard from "@/auth/UserGuard";
import NetworkStatus from "./Pages/NetworkStatus.jsx";
import MerchantDashboard from "./Pages/MerchantDashboard.jsx";
import MerchantSetup from "./Pages/MerchantSetup.jsx";
import MerchantRequests from "./Pages/MerchantRequests.jsx";
import MerchantRequestNew from "./Pages/MerchantRequestNew.jsx";
import StorePublic from "./Pages/StorePublic.jsx";
import PayRequest from "./Pages/PayRequest.jsx";

import MyAccount from "./Pages/MyAccount.jsx";
import MyStore from "./Pages/MyStore.jsx";
import Trade from "./Pages/Trade.jsx";
import Earn from "./Pages/Earn.jsx";
import Converter from "./Pages/Converter.jsx";
import FullStake from "./Pages/FullStake.jsx";
import ChatBYX from "./Pages/ChatBYX.jsx";
import SimpleTrade from "./Pages/SimpleTrade.jsx";
import ProductDetail from "./Pages/ProductDetail.jsx";

function LegacyShell({
  currentPageName,
  children,
}: {
  currentPageName?: string;
  children: ReactNode;
}) {
  return (
    <div className="dark min-h-screen bg-[#070B0F] text-white">
      <Layout currentPageName={currentPageName}>{children}</Layout>
    </div>
  );
}

function LegacyStandalone({ children }: { children: ReactNode }) {
  return <div className="dark min-h-screen bg-[#070B0F] text-white">{children}</div>;
}

export default function SiteRoutes() {
  return (
    <Routes>
      <Route index element={<LegacyStandalone><Home /></LegacyStandalone>} />
      <Route path="auth/login" element={<LegacyStandalone><AuthLogin /></LegacyStandalone>} />
      <Route path="auth/register" element={<LegacyStandalone><AuthRegister /></LegacyStandalone>} />
      <Route
        path="account"
        element={
          <UserGuard>
            <LegacyStandalone>
              <Account />
            </LegacyStandalone>
          </UserGuard>
        }
      />
      <Route path="network" element={<LegacyShell currentPageName="Network"><NetworkStatus /></LegacyShell>} />
      <Route
        path="merchant"
        element={
          <UserGuard>
            <LegacyShell currentPageName="Merchant">
              <MerchantDashboard />
            </LegacyShell>
          </UserGuard>
        }
      />
      <Route
        path="merchant/setup"
        element={
          <UserGuard>
            <LegacyShell currentPageName="Merchant Setup">
              <MerchantSetup />
            </LegacyShell>
          </UserGuard>
        }
      />
      <Route
        path="merchant/requests"
        element={
          <UserGuard>
            <LegacyShell currentPageName="Merchant Requests">
              <MerchantRequests />
            </LegacyShell>
          </UserGuard>
        }
      />
      <Route
        path="merchant/requests/new"
        element={
          <UserGuard>
            <LegacyShell currentPageName="New Request">
              <MerchantRequestNew />
            </LegacyShell>
          </UserGuard>
        }
      />
      <Route path="store/:storeId" element={<LegacyShell currentPageName="Store"><StorePublic /></LegacyShell>} />
      <Route path="pay/:requestId" element={<LegacyShell currentPageName="Pay"><PayRequest /></LegacyShell>} />

      <Route path="dashboard" element={<LegacyShell currentPageName="Dashboard"><Dashboard /></LegacyShell>} />
      <Route path="stores" element={<LegacyShell currentPageName="Stores"><Stores /></LegacyShell>} />
      <Route path="sales-cashback" element={<LegacyShell currentPageName="SalesCashback"><SalesCashback /></LegacyShell>} />
      <Route path="transactions" element={<LegacyShell currentPageName="Transactions"><Transactions /></LegacyShell>} />
      <Route path="wallet" element={<LegacyShell currentPageName="Wallet"><Wallet /></LegacyShell>} />
      <Route path="chat" element={<LegacyShell currentPageName="Chat"><Chat /></LegacyShell>} />

      <Route path="myaccount" element={<LegacyShell currentPageName="MyAccount"><MyAccount /></LegacyShell>} />
      <Route path="mystore" element={<LegacyShell currentPageName="MyStore"><MyStore /></LegacyShell>} />
      <Route path="trade" element={<LegacyShell currentPageName="Trade"><Trade /></LegacyShell>} />
      <Route path="earn" element={<LegacyShell currentPageName="Earn"><Earn /></LegacyShell>} />
      <Route path="converter" element={<LegacyShell currentPageName="Converter"><Converter /></LegacyShell>} />
      <Route path="fullstake" element={<LegacyShell currentPageName="FullStake"><FullStake /></LegacyShell>} />
      <Route path="chatbyx" element={<LegacyShell currentPageName="ChatBYX"><ChatBYX /></LegacyShell>} />
      <Route path="simpletrade" element={<LegacyShell currentPageName="SimpleTrade"><SimpleTrade /></LegacyShell>} />
      <Route path="productdetail" element={<LegacyShell currentPageName="ProductDetail"><ProductDetail /></LegacyShell>} />

      <Route
        path="*"
        element={
          <LegacyStandalone>
            <div className="min-h-screen flex items-center justify-center">
              <div className="text-center">
                <h1 className="text-4xl font-bold mb-2">404</h1>
                <p className="text-white/60 mb-4">Página não encontrada</p>
                <a href="/" className="text-emerald-400 hover:underline">
                  Voltar para o início
                </a>
              </div>
            </div>
          </LegacyStandalone>
        }
      />
    </Routes>
  );
}
