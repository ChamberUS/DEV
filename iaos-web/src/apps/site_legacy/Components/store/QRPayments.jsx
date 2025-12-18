import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion } from 'framer-motion';
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import GlassCard from '@/components/ui/GlassCard';
import { QrCode, Smartphone, Wallet } from 'lucide-react';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { toast } from 'sonner';

export default function QRPayments({ stores, currentStore }) {
  const [formData, setFormData] = useState({
    store_id: currentStore?.id || '',
    amount_aios: '',
    memo: '',
  });
  const [qrData, setQrData] = useState(null);

  const queryClient = useQueryClient();

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => base44.auth.me(),
  });

  const createQRMutation = useMutation({
    mutationFn: (data) => {
      const store = stores.find(s => s.id === data.store_id);
      const qrString = JSON.stringify({
        store_id: data.store_id,
        store_name: store?.name,
        amount: data.amount_aios,
        memo: data.memo,
        timestamp: Date.now()
      });
      
      return base44.entities.QRPayment.create({
        ...data,
        store_name: store?.name,
        seller_email: user?.email,
        qr_data: qrString,
        status: 'pending'
      });
    },
    onSuccess: (data) => {
      toast.success('QR Code gerado!');
      setQrData(data);
      queryClient.invalidateQueries({ queryKey: ['qrPayments'] });
    },
  });

  const handleGenerate = (e) => {
    e.preventDefault();
    if (!formData.store_id || !formData.amount_aios) {
      toast.error('Preencha os campos obrigatórios');
      return;
    }
    createQRMutation.mutate({
      ...formData,
      amount_aios: parseFloat(formData.amount_aios),
    });
  };

  const resetForm = () => {
    setFormData({
      store_id: currentStore?.id || '',
      amount_aios: '',
      memo: '',
    });
    setQrData(null);
  };

  return (
    <GlassCard className="p-6">
      <div className="mb-6">
        <h3 className="text-xl font-bold text-white flex items-center gap-2">
          <QrCode className="w-6 h-6 text-purple-400" />
          Ponto de Venda / QR Payments
        </h3>
        <p className="text-white/50 text-sm">Gere pedidos pagos em AIOS com QR</p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Form */}
        <div>
          <form onSubmit={handleGenerate} className="space-y-4">
            {stores.length > 1 && (
              <div>
                <Label className="text-white/60">Selecionar Loja</Label>
                <Select 
                  value={formData.store_id} 
                  onValueChange={(v) => setFormData({ ...formData, store_id: v })}
                >
                  <SelectTrigger className="bg-white/5 border-white/10 text-white">
                    <SelectValue placeholder="Escolha a loja" />
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

            <div>
              <Label className="text-white/60">Valor (AIOS)</Label>
              <div className="relative">
                <Wallet className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-white/40" />
                <Input
                  type="number"
                  step="0.01"
                  value={formData.amount_aios}
                  onChange={(e) => setFormData({ ...formData, amount_aios: e.target.value })}
                  className="bg-white/5 border-white/10 text-white pl-12 text-lg h-12"
                  placeholder="0.00"
                />
              </div>
            </div>

            <div>
              <Label className="text-white/60">Memo (descrição)</Label>
              <Textarea
                value={formData.memo}
                onChange={(e) => setFormData({ ...formData, memo: e.target.value })}
                className="bg-white/5 border-white/10 text-white h-24"
                placeholder="Descrição do pedido..."
              />
            </div>

            <div className="flex gap-3">
              {qrData && (
                <Button
                  type="button"
                  variant="outline"
                  onClick={resetForm}
                  className="flex-1 border-white/10 text-white"
                >
                  Limpar
                </Button>
              )}
              <Button
                type="submit"
                className="flex-1 bg-gradient-to-r from-purple-500 to-pink-500 hover:from-purple-600 hover:to-pink-600"
                disabled={createQRMutation.isPending}
              >
                {createQRMutation.isPending ? 'Gerando...' : 'Gerar QR Code'}
              </Button>
            </div>
          </form>
        </div>

        {/* QR Code Display */}
        <div className="flex items-center justify-center">
          {qrData ? (
            <motion.div
              initial={{ opacity: 0, scale: 0.8 }}
              animate={{ opacity: 1, scale: 1 }}
              className="text-center"
            >
              <div className="bg-white p-8 rounded-2xl mb-4 inline-block">
                <div className="w-64 h-64 flex items-center justify-center">
                  <img 
                    src={`https://api.qrserver.com/v1/create-qr-code/?size=256x256&data=${encodeURIComponent(qrData.qr_data)}`}
                    alt="QR Code"
                    className="w-full h-full"
                  />
                </div>
              </div>
              <div className="bg-white/5 border border-white/10 rounded-xl p-4">
                <p className="text-white font-bold text-xl mb-2">
                  {parseFloat(qrData.amount_aios).toLocaleString('pt-BR')} AIOS
                </p>
                <p className="text-white/50 text-sm">{qrData.memo || 'Sem descrição'}</p>
                <p className="text-white/30 text-xs mt-2">{qrData.store_name}</p>
              </div>
            </motion.div>
          ) : (
            <div className="text-center py-12">
              <Smartphone className="w-24 h-24 text-white/10 mx-auto mb-4" />
              <p className="text-white/40">Preencha os dados para gerar o QR Code</p>
            </div>
          )}
        </div>
      </div>
    </GlassCard>
  );
}