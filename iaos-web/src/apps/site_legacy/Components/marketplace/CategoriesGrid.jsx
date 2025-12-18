import React from 'react';
import { motion } from 'framer-motion';
import { 
  Laptop, 
  Smartphone, 
  Gamepad2, 
  Headphones, 
  Camera, 
  Watch,
  Tablet,
  Monitor,
  Keyboard,
  Mouse,
  Speaker,
  Tv
} from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';

const categories = [
  { name: 'Notebooks', icon: Laptop, count: 245 },
  { name: 'Celulares', icon: Smartphone, count: 189 },
  { name: 'Tablets', icon: Tablet, count: 87 },
  { name: 'Computadores', icon: Monitor, count: 156 },
  { name: 'Games', icon: Gamepad2, count: 312 },
  { name: 'Áudio', icon: Headphones, count: 198 },
  { name: 'Câmeras', icon: Camera, count: 76 },
  { name: 'Smartwatch', icon: Watch, count: 134 },
  { name: 'Teclados', icon: Keyboard, count: 92 },
  { name: 'Mouses', icon: Mouse, count: 108 },
  { name: 'Caixas de Som', icon: Speaker, count: 65 },
  { name: 'TVs', icon: Tv, count: 54 },
];

export default function CategoriesGrid() {
  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-2xl font-bold text-white">Categorias</h2>
        <button className="text-[#4a9eff] hover:text-[#1a4d2e] text-sm font-medium transition-colors">
          Ver todas →
        </button>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6 gap-4">
        {categories.map((category, index) => (
          <motion.div
            key={category.name}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: index * 0.05 }}
          >
            <GlassCard className="p-6 cursor-pointer hover:border-[#1a4d2e]/60 transition-all group">
              <div className="flex flex-col items-center text-center">
                <div className="w-16 h-16 rounded-full bg-gradient-to-br from-[#4a9eff]/20 to-[#1a4d2e]/20 flex items-center justify-center mb-3 group-hover:scale-110 transition-transform">
                  <category.icon className="w-8 h-8 text-[#4a9eff]" />
                </div>
                <h3 className="text-white font-medium mb-1">{category.name}</h3>
                <p className="text-white/40 text-xs">{category.count} produtos</p>
              </div>
            </GlassCard>
          </motion.div>
        ))}
      </div>
    </div>
  );
}