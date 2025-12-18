import React, { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion } from 'framer-motion';
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { TrendingUp, TrendingDown, Clock, DollarSign, BarChart3, Zap } from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';
import LivePriceChart from '@/apps/site_legacy/Components/trade/LivePriceChart';
import AutoTradePanel from '@/apps/site_legacy/Components/trade/AutoTradePanel';
import { toast } from 'sonner';
import moment from 'moment';

const initialAssets = [
  { id: 'BTC/USD', name: 'Bitcoin', price: 42150, change: 2.5 },
  { id: 'ETH/USD', name: 'Ethereum', price: 2250, change: -1.3 },
  { id: 'BNB/USD', name: 'Binance Coin', price: 315, change: 3.2 },
];

const expiryTimes = [
  { label: '1 min', minutes: 1 },
  { label: '5 min', minutes: 5 },
  { label: '15 min', minutes: 15 },
  { label: '30 min', minutes: 30 },
  { label: '1 hora', minutes: 60 },
];

export default function Trade() {
  const [assets, setAssets] = useState(initialAssets);
  const [selectedAsset, setSelectedAsset] = useState(initialAssets[0]);
  const [amount, setAmount] = useState('');
  const [expiryMinutes, setExpiryMinutes] = useState(5);
  const queryClient = useQueryClient();

  // Simulate real-time price updates for all assets
  useEffect(() => {
    const interval = setInterval(() => {
      setAssets((prevAssets) => 
        prevAssets.map((asset) => {
          const volatility = asset.price * 0.0005;
          const change = (Math.random() - 0.5) * volatility * 2;
          const newPrice = asset.price + change;
          const changePercent = ((newPrice - initialAssets.find(a => a.id === asset.id).price) / initialAssets.find(a => a.id === asset.id).price) * 100;
          
          return {
            ...asset,
            price: newPrice,
            change: changePercent,
          };
        })
      );
    }, 2000);

    return () => clearInterval(interval);
  }, []);

  // Update selected asset when assets change
  useEffect(() => {
    const updated = assets.find(a => a.id === selectedAsset.id);
    if (updated) {
      setSelectedAsset(updated);
    }
  }, [assets]);

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => base44.auth.me(),
  });

  const { data: positions = [] } = useQuery({
    queryKey: ['tradePositions', user?.email],
    queryFn: () => base44.entities.TradePosition.filter(
      { user_email: user?.email },
      '-created_date'
    ),
    enabled: !!user?.email,
  });

  const openPositionMutation = useMutation({
    mutationFn: async ({ direction, customAmount, customExpiry }) => {
      const numAmount = parseFloat(customAmount || amount);
      if (!numAmount || numAmount <= 0) throw new Error('Valor inválido');

      const expiryTime = moment().add(customExpiry || expiryMinutes, 'minutes').toISOString();

      return await base44.entities.TradePosition.create({
        user_email: user?.email,
        asset: selectedAsset.id,
        direction,
        amount_byx: numAmount,
        entry_price: selectedAsset.price,
        expiry_time: expiryTime,
        status: 'open',
        payout_percentage: 85,
      });
    },
    onSuccess: () => {
      toast.success('Posição aberta com sucesso!');
      queryClient.invalidateQueries({ queryKey: ['tradePositions'] });
      setAmount('');
    },
    onError: (error) => {
      toast.error(error.message || 'Erro ao abrir posição');
    },
  });

  const handleAutoTrade = ({ direction, amount: autoAmount, expiryMinutes: autoExpiry }) => {
    openPositionMutation.mutate({ 
      direction, 
      customAmount: autoAmount, 
      customExpiry: autoExpiry 
    });
  };

  const openPositions = positions.filter(p => p.status === 'open');
  const closedPositions = positions.filter(p => p.status !== 'open');

  return (
    <div className="p-6 lg:p-8">
      {/* Header */}
      <motion.div 
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="mb-8"
      >
        <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Trade - Opções Binárias</h1>
        <p className="text-white/50">Negocie com BYX e multiplique seus ganhos</p>
      </motion.div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Trading Panel */}
        <div className="lg:col-span-2 space-y-6">
          {/* Live Price Chart */}
          <GlassCard className="p-6">
            <LivePriceChart asset={selectedAsset} />
          </GlassCard>

          {/* Asset Selection */}
          <GlassCard className="p-6">
            <h3 className="text-white font-semibold mb-4">Selecione o Ativo</h3>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
              {assets.map((asset) => (
                <button
                  key={asset.id}
                  onClick={() => setSelectedAsset(asset)}
                  className={`p-4 rounded-xl transition-all ${
                    selectedAsset.id === asset.id
                      ? 'bg-gradient-to-r from-emerald-500/20 to-cyan-500/20 border-2 border-emerald-500/50'
                      : 'bg-white/5 border border-white/10 hover:bg-white/10'
                  }`}
                >
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-white font-medium">{asset.name}</span>
                    <span className={`text-xs px-2 py-1 rounded-full ${
                      asset.change > 0 ? 'bg-emerald-400/20 text-emerald-400' : 'bg-rose-400/20 text-rose-400'
                    }`}>
                      {asset.change > 0 ? '+' : ''}{asset.change}%
                    </span>
                  </div>
                  <div className="text-left">
                    <p className="text-white/40 text-xs">{asset.id}</p>
                    <p className="text-white font-bold">${asset.price.toLocaleString()}</p>
                  </div>
                </button>
              ))}
            </div>
          </GlassCard>

          {/* Trade Form */}
          <GlassCard gradient className="p-6">
            <h3 className="text-white font-semibold mb-4 flex items-center gap-2">
              <Zap className="w-5 h-5 text-emerald-400" />
              Abrir Posição
            </h3>

            <div className="space-y-4 mb-6">
              <div>
                <Label className="text-white/70 mb-2 block">Valor do Investimento (BYX)</Label>
                <Input
                  type="number"
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                  placeholder="0.00"
                  className="bg-white/5 border-white/10 text-white placeholder:text-white/30 h-12 text-lg"
                />
              </div>

              <div>
                <Label className="text-white/70 mb-2 block">Tempo de Expiração</Label>
                <div className="grid grid-cols-5 gap-2">
                  {expiryTimes.map((time) => (
                    <button
                      key={time.minutes}
                      onClick={() => setExpiryMinutes(time.minutes)}
                      className={`py-2 rounded-lg text-sm transition-all ${
                        expiryMinutes === time.minutes
                          ? 'bg-emerald-500 text-white'
                          : 'bg-white/5 text-white/60 hover:bg-white/10'
                      }`}
                    >
                      {time.label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="bg-white/5 rounded-lg p-4 space-y-2">
                <div className="flex justify-between text-sm">
                  <span className="text-white/50">Retorno potencial</span>
                  <span className="text-emerald-400 font-semibold">
                    {amount ? `${(parseFloat(amount) * 1.85).toFixed(2)} BYX` : '0.00 BYX'}
                  </span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-white/50">Lucro (85%)</span>
                  <span className="text-white font-semibold">
                    {amount ? `+${(parseFloat(amount) * 0.85).toFixed(2)} BYX` : '0.00 BYX'}
                  </span>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <Button
                onClick={() => openPositionMutation.mutate({ direction: 'call' })}
                disabled={!amount || openPositionMutation.isPending}
                className="h-14 bg-gradient-to-r from-emerald-500 to-emerald-600 hover:from-emerald-600 hover:to-emerald-700 text-white font-semibold"
              >
                <TrendingUp className="w-5 h-5 mr-2" />
                CALL (Sobe)
              </Button>
              <Button
                onClick={() => openPositionMutation.mutate({ direction: 'put' })}
                disabled={!amount || openPositionMutation.isPending}
                className="h-14 bg-gradient-to-r from-rose-500 to-rose-600 hover:from-rose-600 hover:to-rose-700 text-white font-semibold"
              >
                <TrendingDown className="w-5 h-5 mr-2" />
                PUT (Desce)
              </Button>
            </div>
          </GlassCard>
        </div>

        {/* Positions Sidebar */}
        <div className="space-y-6">
          {/* Auto Trade Panel */}
          <AutoTradePanel 
            asset={selectedAsset} 
            onAutoTrade={handleAutoTrade}
          />

          {/* Open Positions */}
          <GlassCard className="p-6">
            <h3 className="text-white font-semibold mb-4">Posições Abertas</h3>
            <div className="space-y-3">
              {openPositions.length === 0 ? (
                <div className="text-center py-8 text-white/40 text-sm">
                  Nenhuma posição aberta
                </div>
              ) : (
                openPositions.map((position) => (
                  <div key={position.id} className="p-3 rounded-xl bg-white/5 border border-white/10">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-white font-medium text-sm">{position.asset}</span>
                      <span className={`text-xs px-2 py-1 rounded-full ${
                        position.direction === 'call' 
                          ? 'bg-emerald-400/20 text-emerald-400' 
                          : 'bg-rose-400/20 text-rose-400'
                      }`}>
                        {position.direction === 'call' ? 'CALL' : 'PUT'}
                      </span>
                    </div>
                    <div className="flex items-center justify-between text-xs text-white/50">
                      <span>{position.amount_byx} BYX</span>
                      <span className="flex items-center gap-1">
                        <Clock className="w-3 h-3" />
                        {moment(position.expiry_time).fromNow()}
                      </span>
                    </div>
                  </div>
                ))
              )}
            </div>
          </GlassCard>

          {/* Stats */}
          <GlassCard className="p-6">
            <h3 className="text-white font-semibold mb-4">Estatísticas</h3>
            <div className="space-y-3">
              <div className="flex justify-between">
                <span className="text-white/50 text-sm">Total negociado</span>
                <span className="text-white font-semibold">
                  {positions.reduce((acc, p) => acc + (p.amount_byx || 0), 0).toFixed(2)} BYX
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-white/50 text-sm">Posições ganhas</span>
                <span className="text-emerald-400 font-semibold">
                  {positions.filter(p => p.status === 'won').length}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-white/50 text-sm">Posições perdidas</span>
                <span className="text-rose-400 font-semibold">
                  {positions.filter(p => p.status === 'lost').length}
                </span>
              </div>
            </div>
          </GlassCard>
        </div>
      </div>
    </div>
  );
}