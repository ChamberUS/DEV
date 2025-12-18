import React, { useState, useRef, useEffect } from 'react';
import { Search } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';

const popularSearches = [
  'notebook',
  'mouse alienware',
  'teclado razer',
  'teclado positivo',
  'notebook BUYNNEX',
  'headset gamer',
  'webcam logitech',
  'monitor 144hz',
];

export default function SearchBar() {
  const [isFocused, setIsFocused] = useState(false);
  const [searchValue, setSearchValue] = useState('');
  const containerRef = useRef(null);

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (containerRef.current && !containerRef.current.contains(event.target)) {
        setIsFocused(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  return (
    <div ref={containerRef} className="relative w-full">
      <div className="relative">
        <input
          type="text"
          placeholder="Buscar produtos..."
          value={searchValue}
          onChange={(e) => setSearchValue(e.target.value)}
          onFocus={() => setIsFocused(true)}
          className="w-full h-10 pl-10 pr-4 rounded-lg bg-[#1a1a1a] border border-[#1a4d2e]/30 text-white placeholder:text-white/40 focus:outline-none focus:border-[#1a4d2e]/60 transition-colors"
        />
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-white/40" />
      </div>

      <AnimatePresence>
        {isFocused && (
          <motion.div
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            className="absolute top-full left-0 right-0 mt-2 bg-[#0a0a0a] border border-[#1a4d2e]/50 rounded-lg shadow-2xl overflow-hidden z-50"
          >
            <div className="p-3">
              <p className="text-white/40 text-xs font-semibold mb-2 px-2">Buscas Populares</p>
              <div className="space-y-1">
                {popularSearches.map((search, index) => (
                  <motion.button
                    key={search}
                    initial={{ opacity: 0, x: -10 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: index * 0.05 }}
                    onClick={() => {
                      setSearchValue(search);
                      setIsFocused(false);
                    }}
                    className="w-full text-left px-3 py-2 rounded-lg text-white/70 hover:text-white hover:bg-[#1a4d2e]/30 transition-colors flex items-center gap-2"
                  >
                    <Search className="w-4 h-4 text-white/40" />
                    {search}
                  </motion.button>
                ))}
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}