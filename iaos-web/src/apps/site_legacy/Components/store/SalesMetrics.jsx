import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion } from 'framer-motion';
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import GlassCard from '@/components/ui/GlassCard';
import { 
  TrendingUp, 
  DollarSign, 
  ShoppingBag, 
  Percent,
  Plus,
  Calendar
} from 'lucide-react';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { toast } from 'sonner';

export default function SalesMetrics({ stores, currentStore }) {
  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState({
    store_id: currentStore?.id || '',
    amount_brl: '',
    cashback_aios: '',
    customer_email: '',
    description: '',
  });

  const queryClient = useQueryClient();

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => base44.auth.me(),
  });

  const { data: sales = [] } = useQuery({
    queryKey: ['sales', user?.email],
    queryFn: () => base44.entities.Sale.filter({ seller_email: user?.email }, '-created_date'),
    enabled: !!user?.email,
  });

  const createSaleMutation = useMutation({
    mutationFn: (data) => {
      const store = stores.find(s => s.id === data.store_id);
      return base44.entities.Sale.create({
        ...data,
        store_name: store?.name,
        seller_email: user?.email,
        payment_method: 'manual'
      });
    },
    onSuccess: () => {
      toast.success('Venda registrada com sucesso!');
      queryClient.invalidateQueries({ queryKey: ['sales'] });
      setShowForm(false);
      setFormData({
        store_id: currentStore?.id || '',
        amount_brl: '',
        cashback_aios: '',
        customer_email: '',
        description: '',
      });
    },
  });

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!formData.store_id || !formData.amount_brl) {
      toast.error('Preencha os campos obrigatórios');
      return;
    }
    createSaleMutation.mutate({
      ...formData,
      amount_brl: parseFloat(formData.amount_brl),
      cashback_aios: parseFloat(formData.cashback_aios || 0),
    });
  };

  // Calcular métricas do mês atual
  const now = new Date();
  const firstDayOfMonth = new Date(now.getFullYear(), now.getMonth(), 1);
  
  const monthlySales = sales.filter(sale => {
    const saleDate = new Date(sale.created_date);
    return saleDate >= firstDayOfMonth;
  });

  const totalSales = monthlySales.reduce((acc, sale) => acc + sale.amount_brl, 0);
  const avgTicket = monthlySales.length > 0 ? totalSales / monthlySales.length : 0;
  const totalCashback = monthlySales.reduce((acc, sale) => acc + (sale.cashback_aios || 0), 0);

  return (
    <div>
      <GlassCard className="p-6">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h3 className="text-xl font-bold text-white flex items-center gap-2">
              <TrendingUp className="w-6 h-6 text-emerald-400" />
              Vendas & Cashback
            </h3>
            <p className="text-white/50 text-sm">Registre vendas em R$ e ofereça cashback em AIOS</p>
          </div>
          <Button 
            onClick={() => setShowForm(true)}
            className="bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600"
          >
            <Plus className="w-4 h-4 mr-2" />
            Nova Venda
          </Button>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          <div className="bg-white/5 border border-white/10 rounded-xl p-4">
            <div className="flex items-center gap-2 mb-2">
              <DollarSign className="w-5 h-5 text-green-400" />
              <p className="text-white/60 text-sm">Total de Vendas</p>
            </div>
            <p className="text-2xl font-bold text-white">
              R$ {totalSales.toLocaleString('pt-BR', { minimumFractionDigits: 2 })}
            </p>
            <p className="text-white/40 text-xs mt-1 flex items-center gap-1">
              <Calendar className="w-3 h-3" /> Este mês
            </p>
          </div>

          <div className="bg-white/5 border border-white/10 rounded-xl p-4">
            <div className="flex items-center gap-2 mb-2">
              <ShoppingBag className="w-5 h-5 text-blue-400" />
              <p className="text-white/60 text-sm">Ticket Médio</p>
            </div>
            <p className="text-2xl font-bold text-white">
              R$ {avgTicket.toLocaleString('pt-BR', { minimumFractionDigits: 2 })}
            </p>
            <p className="text-white/40 text-xs mt-1">Por transação</p>
          </div>

          <div className="bg-white/5 border border-white/10 rounded-xl p-4">
            <div className="flex items-center gap-2 mb-2">
              <TrendingUp className="w-5 h-5 text-purple-400" />
              <p className="text-white/60 text-sm">Vendas no Mês</p>
            </div>
            <p className="text-2xl font-bold text-white">{monthlySales.length}</p>
            <p className="text-white/40 text-xs mt-1">Transações</p>
          </div>

          <div className="bg-white/5 border border-white/10 rounded-xl p-4">
            <div className="flex items-center gap-2 mb-2">
              <Percent className="w-5 h-5 text-amber-400" />
              <p className="text-white/60 text-sm">Cashback Total</p>
            </div>
            <p className="text-2xl font-bold text-white">{totalCashback.toLocaleString('pt-BR')} AIOS</p>
            <p className="text-white/40 text-xs mt-1">Distribuído</p>
          </div>
        </div>
      </GlassCard>

      {/* Form Dialog */}
      <Dialog open={showForm} onOpenChange={setShowForm}>
        <DialogContent className="bg-[#0d1320] border-white/10 text-white max-w-md">
          <DialogHeader>
            <DialogTitle className="text-white">Registrar Nova Venda</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleSubmit} className="space-y-4">
            {stores.length > 1 && (
              <div>
                <Label className="text-white/60">Loja</Label>
                <Select 
                  value={formData.store_id} 
                  onValueChange={(v) => setFormData({ ...formData, store_id: v })}
                >
                  <SelectTrigger className="bg-white/5 border-white/10 text-white">
                    <SelectValue placeholder="Selecione a loja" />
                  </SelectTrigger>
                  <SelectContent className="bg-[#1a2030] border-white/10">
                    {stores.map((store) => (
                      <SelectItem key={store.id} value={store.id} className="text-white hover:bg-white/10">
                        {store.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            )}

            <div className="grid grid-cols-2 gap-4">
              <div>
                <Label className="text-white/60">Valor (R$)</Label>
                <Input
                  type="number"
                  step="0.01"
                  value={formData.amount_brl}
                  onChange={(e) => setFormData({ ...formData, amount_brl: e.target.value })}
                  className="bg-white/5 border-white/10 text-white"
                  placeholder="0.00"
                />
              </div>
              <div>
                <Label className="text-white/60">Cashback (AIOS)</Label>
                <Input
                  type="number"
                  step="0.01"
                  value={formData.cashback_aios}
                  onChange={(e) => setFormData({ ...formData, cashback_aios: e.target.value })}
                  className="bg-white/5 border-white/10 text-white"
                  placeholder="0.00"
                />
              </div>
            </div>

            <div>
              <Label className="text-white/60">Email do Cliente (opcional)</Label>
              <Input
                type="email"
                value={formData.customer_email}
                onChange={(e) => setFormData({ ...formData, customer_email: e.target.value })}
                className="bg-white/5 border-white/10 text-white"
                placeholder="cliente@email.com"
              />
            </div>

            <div>
              <Label className="text-white/60">Descrição</Label>
              <Textarea
                value={formData.description}
                onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                className="bg-white/5 border-white/10 text-white h-20"
                placeholder="Detalhes da venda..."
              />
            </div>

            <div className="flex gap-3 pt-4">
              <Button
                type="button"
                variant="outline"
                onClick={() => setShowForm(false)}
                className="flex-1 border-white/10 text-white"
              >
                Cancelar
              </Button>
              <Button
                type="submit"
                className="flex-1 bg-gradient-to-r from-emerald-500 to-cyan-500"
                disabled={createSaleMutation.isPending}
              >
                {createSaleMutation.isPending ? 'Salvando...' : 'Registrar'}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}