import React, { useState, useEffect } from 'react';
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Play, Pause, Settings2, TrendingUp, TrendingDown } from 'lucide-react';
import GlassCard from '../ui/GlassCard';
import { toast } from 'sonner';

export default function AutoTradePanel({ asset, onAutoTrade }) {
  const [isActive, setIsActive] = useState(false);
  const [config, setConfig] = useState({
    amount: '',
    targetPrice: '',
    direction: 'call',
    priceCondition: 'above', // above, below
    expiryMinutes: 5,
  });

  useEffect(() => {
    if (!isActive) return;

    const interval = setInterval(() => {
      const targetPrice = parseFloat(config.targetPrice);
      const amount = parseFloat(config.amount);

      if (!targetPrice || !amount) return;

      // Check if price condition is met
      const conditionMet = config.priceCondition === 'above' 
        ? asset.price >= targetPrice 
        : asset.price <= targetPrice;

      if (conditionMet) {
        // Execute auto trade
        onAutoTrade({
          direction: config.direction,
          amount: amount,
          expiryMinutes: config.expiryMinutes,
        });

        toast.success(`Auto-trade executado: ${config.direction === 'call' ? 'CALL' : 'PUT'} - ${amount} BYX`);
        setIsActive(false); // Stop after execution
      }
    }, 2000);

    return () => clearInterval(interval);
  }, [isActive, config, asset.price]);

  const handleToggle = () => {
    if (!config.amount || !config.targetPrice) {
      toast.error('Configure todos os parâmetros primeiro');
      return;
    }
    setIsActive(!isActive);
    if (!isActive) {
      toast.info('Auto-trade ativado - monitorando preço...');
    } else {
      toast.info('Auto-trade desativado');
    }
  };

  return (
    <GlassCard className="p-6">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-2">
          <Settings2 className="w-5 h-5 text-cyan-400" />
          <h3 className="text-white font-semibold">Auto Trade</h3>
        </div>
        <div className={`px-3 py-1 rounded-full text-xs font-semibold ${
          isActive 
            ? 'bg-emerald-500/20 text-emerald-400 animate-pulse' 
            : 'bg-white/5 text-white/40'
        }`}>
          {isActive ? 'ATIVO' : 'INATIVO'}
        </div>
      </div>

      <div className="space-y-4">
        <div>
          <Label className="text-white/70 mb-2 block">Valor do Trade (BYX)</Label>
          <Input
            type="number"
            value={config.amount}
            onChange={(e) => setConfig({ ...config, amount: e.target.value })}
            placeholder="0.00"
            disabled={isActive}
            className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
          />
        </div>

        <div>
          <Label className="text-white/70 mb-2 block">Preço Alvo ($)</Label>
          <Input
            type="number"
            value={config.targetPrice}
            onChange={(e) => setConfig({ ...config, targetPrice: e.target.value })}
            placeholder={`Ex: ${asset.price}`}
            disabled={isActive}
            className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
          />
        </div>

        <div>
          <Label className="text-white/70 mb-2 block">Condição</Label>
          <div className="grid grid-cols-2 gap-2">
            <button
              onClick={() => setConfig({ ...config, priceCondition: 'above' })}
              disabled={isActive}
              className={`py-2 rounded-lg text-sm transition-all ${
                config.priceCondition === 'above'
                  ? 'bg-emerald-500 text-white'
                  : 'bg-white/5 text-white/60 hover:bg-white/10'
              } disabled:opacity-50`}
            >
              Preço ≥ Alvo
            </button>
            <button
              onClick={() => setConfig({ ...config, priceCondition: 'below' })}
              disabled={isActive}
              className={`py-2 rounded-lg text-sm transition-all ${
                config.priceCondition === 'below'
                  ? 'bg-rose-500 text-white'
                  : 'bg-white/5 text-white/60 hover:bg-white/10'
              } disabled:opacity-50`}
            >
              Preço ≤ Alvo
            </button>
          </div>
        </div>

        <div>
          <Label className="text-white/70 mb-2 block">Direção</Label>
          <div className="grid grid-cols-2 gap-2">
            <button
              onClick={() => setConfig({ ...config, direction: 'call' })}
              disabled={isActive}
              className={`py-2 rounded-lg text-sm transition-all flex items-center justify-center gap-2 ${
                config.direction === 'call'
                  ? 'bg-emerald-500 text-white'
                  : 'bg-white/5 text-white/60 hover:bg-white/10'
              } disabled:opacity-50`}
            >
              <TrendingUp className="w-4 h-4" />
              CALL
            </button>
            <button
              onClick={() => setConfig({ ...config, direction: 'put' })}
              disabled={isActive}
              className={`py-2 rounded-lg text-sm transition-all flex items-center justify-center gap-2 ${
                config.direction === 'put'
                  ? 'bg-rose-500 text-white'
                  : 'bg-white/5 text-white/60 hover:bg-white/10'
              } disabled:opacity-50`}
            >
              <TrendingDown className="w-4 h-4" />
              PUT
            </button>
          </div>
        </div>

        <div className="pt-4 border-t border-white/10">
          <div className="bg-white/5 rounded-lg p-3 mb-4 text-xs text-white/60">
            {isActive ? (
              <p>
                🎯 Aguardando: {asset.name} {config.priceCondition === 'above' ? '≥' : '≤'} ${config.targetPrice}
                <br />
                Preço atual: ${asset.price.toFixed(2)}
              </p>
            ) : (
              <p>Configure os parâmetros e ative para executar trades automaticamente</p>
            )}
          </div>
          
          <Button
            onClick={handleToggle}
            className={`w-full h-12 font-semibold ${
              isActive
                ? 'bg-rose-500 hover:bg-rose-600 text-white'
                : 'bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white'
            }`}
          >
            {isActive ? (
              <>
                <Pause className="w-5 h-5 mr-2" />
                Parar Auto Trade
              </>
            ) : (
              <>
                <Play className="w-5 h-5 mr-2" />
                Iniciar Auto Trade
              </>
            )}
          </Button>
        </div>
      </div>
    </GlassCard>
  );
}