import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion } from 'framer-motion';
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Receipt, Store as StoreIcon, DollarSign, Wallet, TrendingUp } from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { toast } from 'sonner';

// Taxa de cashback: 500 microBYX por real (exemplo)
const CASHBACK_RATE_MICRO_BYX_PER_REAL = 500;

export default function SalesCashback() {
  const [selectedStore, setSelectedStore] = useState('');
  const [saleValue, setSaleValue] = useState('');
  const [customerAddress, setCustomerAddress] = useState('');
  const queryClient = useQueryClient();

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => base44.auth.me(),
  });

  const { data: stores = [] } = useQuery({
    queryKey: ['myStores', user?.email],
    queryFn: () => base44.entities.Store.filter({ owner_email: user?.email }),
    enabled: !!user?.email,
  });

  const { data: sales = [] } = useQuery({
    queryKey: ['recentSales', user?.email],
    queryFn: () => base44.entities.Transaction.filter(
      { type: 'purchase', to_user: user?.email },
      '-created_date',
      10
    ),
    enabled: !!user?.email,
  });

  const registerSaleMutation = useMutation({
    mutationFn: async () => {
      const numValue = parseFloat(saleValue);
      if (!numValue || numValue <= 0) {
        throw new Error('Valor da venda inválido');
      }

      if (!selectedStore) {
        throw new Error('Selecione uma loja');
      }

      // Converter para centavos
      const valueInCents = Math.round(numValue * 100);

      // Calcular cashback em microBYX
      const cashbackMicroByx = customerAddress 
        ? Math.round(numValue * CASHBACK_RATE_MICRO_BYX_PER_REAL)
        : 0;

      // Registrar transação
      const transaction = await base44.entities.Transaction.create({
        type: 'purchase',
        amount: numValue,
        description: `Venda - Loja: ${stores.find(s => s.id === selectedStore)?.name}`,
        to_user: user?.email,
        from_user: customerAddress || 'Cliente sem endereço',
        status: 'completed',
      });

      // Atualizar total de vendas da loja
      const store = stores.find(s => s.id === selectedStore);
      if (store) {
        await base44.entities.Store.update(selectedStore, {
          total_sales: (store.total_sales || 0) + 1,
        });
      }

      return { transaction, cashbackMicroByx, valueInCents };
    },
    onSuccess: ({ cashbackMicroByx }) => {
      if (cashbackMicroByx > 0) {
        toast.success(`Venda registrada! Cashback creditado: ${cashbackMicroByx.toLocaleString()} microBYX para o cliente.`);
      } else {
        toast.success('Venda registrada com sucesso!');
      }
      queryClient.invalidateQueries({ queryKey: ['recentSales'] });
      queryClient.invalidateQueries({ queryKey: ['myStores'] });
      setSaleValue('');
      setCustomerAddress('');
    },
    onError: (error) => {
      if (error.message.includes('limite')) {
        toast.error('Valor da venda acima do limite permitido.');
      } else if (error.message.includes('endereço')) {
        toast.error('Endereço do cliente inválido.');
      } else {
        toast.error(error.message || 'Erro ao registrar venda');
      }
    },
  });

  // Calcular preview do cashback
  const cashbackPreview = saleValue && customerAddress
    ? Math.round(parseFloat(saleValue) * CASHBACK_RATE_MICRO_BYX_PER_REAL)
    : 0;

  // Auto-selecionar loja se houver apenas uma
  React.useEffect(() => {
    if (stores.length === 1 && !selectedStore) {
      setSelectedStore(stores[0].id);
    }
  }, [stores, selectedStore]);

  const totalSalesAmount = sales.reduce((acc, sale) => acc + (sale.amount || 0), 0);
  const totalSalesCount = sales.length;

  return (
    <div className="p-6 lg:p-8">
      {/* Header */}
      <motion.div 
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="mb-8"
      >
        <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Vendas & Cashback</h1>
        <p className="text-white/50">Registre vendas e ofereça cashback em BYX para seus clientes</p>
      </motion.div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Form Section */}
        <div className="lg:col-span-2 space-y-6">
          {/* Stats */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <GlassCard className="p-4">
              <div className="flex items-center gap-3">
                <Receipt className="w-8 h-8 text-emerald-400" />
                <div>
                  <p className="text-white/40 text-xs">Total de Vendas</p>
                  <p className="text-white font-bold text-lg">{totalSalesCount}</p>
                </div>
              </div>
            </GlassCard>
            <GlassCard className="p-4">
              <div className="flex items-center gap-3">
                <DollarSign className="w-8 h-8 text-cyan-400" />
                <div>
                  <p className="text-white/40 text-xs">Valor Total</p>
                  <p className="text-white font-bold text-lg">{totalSalesAmount.toFixed(2)} BYX</p>
                </div>
              </div>
            </GlassCard>
            <GlassCard className="p-4">
              <div className="flex items-center gap-3">
                <TrendingUp className="w-8 h-8 text-amber-400" />
                <div>
                  <p className="text-white/40 text-xs">Ticket Médio</p>
                  <p className="text-white font-bold text-lg">
                    {totalSalesCount > 0 ? (totalSalesAmount / totalSalesCount).toFixed(2) : '0.00'} BYX
                  </p>
                </div>
              </div>
            </GlassCard>
          </div>

          {/* Sale Form */}
          <GlassCard gradient className="p-6">
            <h3 className="text-white font-semibold mb-6 flex items-center gap-2">
              <Receipt className="w-5 h-5 text-emerald-400" />
              Registrar Nova Venda
            </h3>

            <div className="space-y-5">
              {/* Store Selection */}
              {stores.length > 1 && (
                <div>
                  <Label className="text-white/70 mb-2 block">Selecione a Loja</Label>
                  <Select value={selectedStore} onValueChange={setSelectedStore}>
                    <SelectTrigger className="bg-white/5 border-white/10 text-white h-12">
                      <SelectValue placeholder="Escolha uma loja" />
                    </SelectTrigger>
                    <SelectContent className="bg-[#1a2030] border-white/10">
                      {stores.map((store) => (
                        <SelectItem key={store.id} value={store.id} className="text-white hover:bg-white/10">
                          <div className="flex items-center gap-2">
                            <StoreIcon className="w-4 h-4" />
                            {store.name}
                          </div>
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}

              {stores.length === 1 && (
                <div>
                  <Label className="text-white/70 mb-2 block">Loja</Label>
                  <div className="bg-white/5 border border-white/10 rounded-lg p-3 flex items-center gap-2">
                    <StoreIcon className="w-5 h-5 text-emerald-400" />
                    <span className="text-white font-medium">{stores[0].name}</span>
                  </div>
                </div>
              )}

              {stores.length === 0 && (
                <div className="bg-amber-400/10 border border-amber-400/20 rounded-lg p-4 text-center">
                  <p className="text-amber-400 text-sm">
                    Você precisa criar uma loja primeiro para registrar vendas.
                  </p>
                </div>
              )}

              {/* Sale Value */}
              <div>
                <Label className="text-white/70 mb-2 block">Valor da Venda (BYX)</Label>
                <div className="relative">
                  <DollarSign className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-white/40" />
                  <Input
                    type="number"
                    step="0.01"
                    value={saleValue}
                    onChange={(e) => setSaleValue(e.target.value)}
                    placeholder="0.00"
                    className="pl-10 bg-white/5 border-white/10 text-white placeholder:text-white/30 h-12 text-lg"
                    disabled={stores.length === 0}
                  />
                </div>
                {saleValue && (
                  <p className="text-white/40 text-xs mt-1">
                    Em centavos: {Math.round(parseFloat(saleValue) * 100).toLocaleString()}
                  </p>
                )}
              </div>

              {/* Customer Address */}
              <div>
                <Label className="text-white/70 mb-2 block">
                  Endereço do Cliente (opcional)
                  <span className="text-white/40 text-xs ml-2">• bech32 BYX address</span>
                </Label>
                <div className="relative">
                  <Wallet className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-white/40" />
                  <Input
                    value={customerAddress}
                    onChange={(e) => setCustomerAddress(e.target.value)}
                    placeholder="byx1..."
                    className="pl-10 bg-white/5 border-white/10 text-white placeholder:text-white/30 h-12"
                    disabled={stores.length === 0}
                  />
                </div>
                <p className="text-white/40 text-xs mt-1">
                  {customerAddress 
                    ? '✓ Cashback será creditado neste endereço' 
                    : 'Sem endereço = venda sem cashback'}
                </p>
              </div>

              {/* Cashback Preview */}
              {cashbackPreview > 0 && (
                <motion.div
                  initial={{ opacity: 0, y: -10 }}
                  animate={{ opacity: 1, y: 0 }}
                  className="bg-gradient-to-r from-emerald-500/10 to-cyan-500/10 border border-emerald-500/20 rounded-lg p-4"
                >
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="text-white/60 text-sm mb-1">Preview do Cashback</p>
                      <p className="text-emerald-400 font-bold text-xl">
                        ~ {cashbackPreview.toLocaleString()} microBYX
                      </p>
                    </div>
                    <TrendingUp className="w-10 h-10 text-emerald-400/40" />
                  </div>
                  <p className="text-white/40 text-xs mt-2">
                    Taxa: {CASHBACK_RATE_MICRO_BYX_PER_REAL} microBYX por BYX
                  </p>
                </motion.div>
              )}

              {/* Submit Button */}
              <Button
                onClick={() => registerSaleMutation.mutate()}
                disabled={!saleValue || !selectedStore || registerSaleMutation.isPending || stores.length === 0}
                className="w-full h-12 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-semibold"
              >
                <Receipt className="w-5 h-5 mr-2" />
                {registerSaleMutation.isPending ? 'Registrando...' : 'Registrar Venda'}
              </Button>
            </div>
          </GlassCard>
        </div>

        {/* Recent Sales Sidebar */}
        <div>
          <GlassCard className="p-6">
            <h3 className="text-white font-semibold mb-4">Vendas Recentes</h3>
            <div className="space-y-3">
              {sales.length === 0 ? (
                <div className="text-center py-8 text-white/40 text-sm">
                  Nenhuma venda registrada
                </div>
              ) : (
                sales.map((sale) => (
                  <div key={sale.id} className="p-3 rounded-xl bg-white/5 border border-white/10">
                    <div className="flex justify-between items-start mb-2">
                      <div className="flex-1">
                        <p className="text-white font-medium text-sm truncate">
                          {sale.description || 'Venda'}
                        </p>
                        <p className="text-white/40 text-xs">
                          {new Date(sale.created_date).toLocaleDateString('pt-BR')}
                        </p>
                      </div>
                      <p className="text-emerald-400 font-bold text-sm">
                        {sale.amount?.toFixed(2)} BYX
                      </p>
                    </div>
                    {sale.from_user && sale.from_user !== 'Cliente sem endereço' && (
                      <div className="flex items-center gap-1 text-xs text-white/40 mt-2">
                        <Wallet className="w-3 h-3" />
                        <span className="truncate">{sale.from_user.substring(0, 20)}...</span>
                      </div>
                    )}
                  </div>
                ))
              )}
            </div>
          </GlassCard>
        </div>
      </div>
    </div>
  );
}