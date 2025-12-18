import React, { useState, useEffect } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { TrendingUp, TrendingDown } from 'lucide-react';

export default function LivePriceChart({ asset }) {
  const [priceData, setPriceData] = useState([]);
  const [currentPrice, setCurrentPrice] = useState(asset.price);
  const [priceChange, setPriceChange] = useState(0);

  useEffect(() => {
    // Initialize with some historical data
    const now = Date.now();
    const initialData = Array.from({ length: 30 }, (_, i) => ({
      time: now - (29 - i) * 2000,
      price: asset.price + (Math.random() - 0.5) * (asset.price * 0.02),
    }));
    setPriceData(initialData);
    setCurrentPrice(asset.price);

    // Simulate real-time price updates
    const interval = setInterval(() => {
      const volatility = asset.price * 0.001; // 0.1% volatility
      const change = (Math.random() - 0.5) * volatility * 2;
      const newPrice = currentPrice + change;
      const changePercent = ((newPrice - asset.price) / asset.price) * 100;

      setCurrentPrice(newPrice);
      setPriceChange(changePercent);

      setPriceData((prev) => {
        const newData = [...prev.slice(1), {
          time: Date.now(),
          price: newPrice,
        }];
        return newData;
      });
    }, 2000);

    return () => clearInterval(interval);
  }, [asset.id]);

  const formatTime = (timestamp) => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  };

  const CustomTooltip = ({ active, payload }) => {
    if (active && payload && payload.length) {
      return (
        <div className="bg-[#1a2030] border border-white/10 rounded-lg p-3">
          <p className="text-white font-semibold">${payload[0].value.toFixed(2)}</p>
          <p className="text-white/50 text-xs">{formatTime(payload[0].payload.time)}</p>
        </div>
      );
    }
    return null;
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <div>
          <h3 className="text-white font-semibold text-lg">{asset.name}</h3>
          <p className="text-white/40 text-sm">{asset.id}</p>
        </div>
        <div className="text-right">
          <p className="text-2xl font-bold text-white">${currentPrice.toFixed(2)}</p>
          <div className={`flex items-center gap-1 text-sm ${
            priceChange >= 0 ? 'text-emerald-400' : 'text-rose-400'
          }`}>
            {priceChange >= 0 ? <TrendingUp className="w-4 h-4" /> : <TrendingDown className="w-4 h-4" />}
            <span>{priceChange >= 0 ? '+' : ''}{priceChange.toFixed(2)}%</span>
          </div>
        </div>
      </div>

      <ResponsiveContainer width="100%" height={300}>
        <LineChart data={priceData}>
          <CartesianGrid strokeDasharray="3 3" stroke="#ffffff10" />
          <XAxis 
            dataKey="time" 
            tickFormatter={formatTime}
            stroke="#ffffff30"
            tick={{ fill: '#ffffff50', fontSize: 12 }}
          />
          <YAxis 
            domain={['dataMin - 10', 'dataMax + 10']}
            stroke="#ffffff30"
            tick={{ fill: '#ffffff50', fontSize: 12 }}
          />
          <Tooltip content={<CustomTooltip />} />
          <Line 
            type="monotone" 
            dataKey="price" 
            stroke="#10b981" 
            strokeWidth={2}
            dot={false}
            animationDuration={300}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}