import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';

const promotions = [
  {
    id: 1,
    title: 'Notebooks em Promoção',
    description: 'Até 30% OFF em notebooks selecionados',
    image: 'https://images.unsplash.com/photo-1496181133206-80ce9b88a853?w=1200&h=400&fit=crop',
    color: 'from-blue-600 to-blue-800',
  },
  {
    id: 2,
    title: 'Smartphones com Desconto',
    description: 'Os melhores celulares com preços especiais',
    image: 'https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?w=1200&h=400&fit=crop',
    color: 'from-purple-600 to-purple-800',
  },
  {
    id: 3,
    title: 'Games e Acessórios',
    description: 'Compre com BYX e ganhe cashback',
    image: 'https://images.unsplash.com/photo-1593305841991-05c297ba4575?w=1200&h=400&fit=crop',
    color: 'from-green-600 to-green-800',
  },
  {
    id: 4,
    title: 'Eletrônicos do Dia',
    description: 'Ofertas especiais por tempo limitado',
    image: 'https://images.unsplash.com/photo-1498049794561-7780e7231661?w=1200&h=400&fit=crop',
    color: 'from-orange-600 to-orange-800',
  },
];

export default function PromotionCarousel() {
  const [currentIndex, setCurrentIndex] = useState(0);

  useEffect(() => {
    const interval = setInterval(() => {
      setCurrentIndex((prev) => (prev + 1) % promotions.length);
    }, 5000);
    return () => clearInterval(interval);
  }, []);

  const goToNext = () => {
    setCurrentIndex((prev) => (prev + 1) % promotions.length);
  };

  const goToPrev = () => {
    setCurrentIndex((prev) => (prev - 1 + promotions.length) % promotions.length);
  };

  return (
    <div className="relative w-full h-[400px] rounded-2xl overflow-hidden bg-[#0a0a0a]">
      <AnimatePresence mode="wait">
        <motion.div
          key={currentIndex}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.5 }}
          className="absolute inset-0"
        >
          <div className={`absolute inset-0 bg-gradient-to-r ${promotions[currentIndex].color} opacity-90`} />
          <img
            src={promotions[currentIndex].image}
            alt={promotions[currentIndex].title}
            className="absolute inset-0 w-full h-full object-cover mix-blend-overlay"
          />
          <div className="absolute inset-0 flex items-center justify-center text-center px-8">
            <div>
              <h2 className="text-4xl md:text-5xl font-bold text-white mb-4">
                {promotions[currentIndex].title}
              </h2>
              <p className="text-xl text-white/90">
                {promotions[currentIndex].description}
              </p>
            </div>
          </div>
        </motion.div>
      </AnimatePresence>

      {/* Navigation Buttons */}
      <Button
        onClick={goToPrev}
        variant="ghost"
        size="icon"
        className="absolute left-4 top-1/2 -translate-y-1/2 bg-black/30 hover:bg-black/50 text-white"
      >
        <ChevronLeft className="w-6 h-6" />
      </Button>
      <Button
        onClick={goToNext}
        variant="ghost"
        size="icon"
        className="absolute right-4 top-1/2 -translate-y-1/2 bg-black/30 hover:bg-black/50 text-white"
      >
        <ChevronRight className="w-6 h-6" />
      </Button>

      {/* Dots Indicator */}
      <div className="absolute bottom-4 left-1/2 -translate-x-1/2 flex gap-2">
        {promotions.map((_, index) => (
          <button
            key={index}
            onClick={() => setCurrentIndex(index)}
            className={`w-2 h-2 rounded-full transition-all ${
              index === currentIndex ? 'bg-white w-8' : 'bg-white/50'
            }`}
          />
        ))}
      </div>
    </div>
  );
}