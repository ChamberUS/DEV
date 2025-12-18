import React, { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { X, ArrowRight, ArrowLeft, Check } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import GlassCard from '@/components/ui/GlassCard';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { toast } from 'sonner';

const categories = [
  { value: 'notebooks', label: 'Notebooks', icon: '💻' },
  { value: 'celulares', label: 'Celulares', icon: '📱' },
  { value: 'tablets', label: 'Tablets', icon: '📱' },
  { value: 'games', label: 'Games', icon: '🎮' },
  { value: 'audio', label: 'Áudio', icon: '🎧' },
  { value: 'cameras', label: 'Câmeras', icon: '📷' },
  { value: 'smartwatch', label: 'Smartwatch', icon: '⌚' },
  { value: 'acessorios', label: 'Acessórios', icon: '🔌' },
];

export default function CreateStoreWizard({ user, onClose, onComplete }) {
  const [step, setStep] = useState(1);
  const [storeData, setStoreData] = useState({
    name: '',
    employees: '',
    is_owner: true,
    categories: [],
  });

  const queryClient = useQueryClient();

  const createStoreMutation = useMutation({
    mutationFn: (data) => base44.entities.Store.create({
      name: data.name,
      description: `Loja de ${data.categories.join(', ')}`,
      category: data.categories[0] || 'eletronicos',
      owner_email: user?.email,
      rating: 5,
      total_sales: 0,
    }),
    onSuccess: () => {
      toast.success('Loja criada com sucesso!');
      onComplete();
    },
  });

  const handleNext = () => {
    if (step === 1 && !storeData.name) {
      toast.error('Digite o nome da sua loja');
      return;
    }
    if (step === 2 && !storeData.employees) {
      toast.error('Informe quantas pessoas trabalham com você');
      return;
    }
    if (step === 4 && storeData.categories.length === 0) {
      toast.error('Selecione pelo menos uma categoria');
      return;
    }
    
    if (step === 4) {
      createStoreMutation.mutate(storeData);
    } else {
      setStep(step + 1);
    }
  };

  const toggleCategory = (category) => {
    if (storeData.categories.includes(category)) {
      setStoreData({
        ...storeData,
        categories: storeData.categories.filter(c => c !== category),
      });
    } else {
      setStoreData({
        ...storeData,
        categories: [...storeData.categories, category],
      });
    }
  };

  return (
    <div className="fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <motion.div
        initial={{ opacity: 0, scale: 0.9 }}
        animate={{ opacity: 1, scale: 1 }}
        className="w-full max-w-2xl"
      >
        <GlassCard className="p-8">
          <div className="flex items-center justify-between mb-8">
            <h2 className="text-2xl font-bold text-white">Criar Loja</h2>
            <button
              onClick={onClose}
              className="text-white/60 hover:text-white transition-colors"
            >
              <X className="w-6 h-6" />
            </button>
          </div>

          {/* Progress Bar */}
          <div className="flex gap-2 mb-8">
            {[1, 2, 3, 4].map((s) => (
              <div
                key={s}
                className={`h-2 flex-1 rounded-full transition-all ${
                  s <= step ? 'bg-[#4a9eff]' : 'bg-white/10'
                }`}
              />
            ))}
          </div>

          <AnimatePresence mode="wait">
            {step === 1 && (
              <motion.div
                key="step1"
                initial={{ opacity: 0, x: 20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: -20 }}
                className="space-y-6"
              >
                <div>
                  <Label className="text-white text-lg mb-2">Como será o nome da sua loja?</Label>
                  <Input
                    value={storeData.name}
                    onChange={(e) => setStoreData({ ...storeData, name: e.target.value })}
                    placeholder="Digite o nome da sua loja"
                    className="bg-white/5 border-white/10 text-white text-lg h-14"
                  />
                </div>
              </motion.div>
            )}

            {step === 2 && (
              <motion.div
                key="step2"
                initial={{ opacity: 0, x: 20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: -20 }}
                className="space-y-6"
              >
                <div>
                  <Label className="text-white text-lg mb-2">Quantas pessoas trabalham com você?</Label>
                  <Input
                    type="number"
                    value={storeData.employees}
                    onChange={(e) => setStoreData({ ...storeData, employees: e.target.value })}
                    placeholder="Ex: 5"
                    className="bg-white/5 border-white/10 text-white text-lg h-14"
                  />
                </div>
              </motion.div>
            )}

            {step === 3 && (
              <motion.div
                key="step3"
                initial={{ opacity: 0, x: 20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: -20 }}
                className="space-y-6"
              >
                <Label className="text-white text-lg mb-4 block">
                  Você é o dono da {storeData.name}?
                </Label>
                <div className="grid grid-cols-2 gap-4">
                  <button
                    onClick={() => setStoreData({ ...storeData, is_owner: true })}
                    className={`p-6 rounded-xl border-2 transition-all ${
                      storeData.is_owner
                        ? 'border-[#4a9eff] bg-[#4a9eff]/20'
                        : 'border-white/10 bg-white/5 hover:bg-white/10'
                    }`}
                  >
                    <p className="text-white text-xl mb-2">Sim</p>
                    <p className="text-white/60 text-sm">Sou proprietário</p>
                  </button>
                  <button
                    onClick={() => setStoreData({ ...storeData, is_owner: false })}
                    className={`p-6 rounded-xl border-2 transition-all ${
                      !storeData.is_owner
                        ? 'border-[#4a9eff] bg-[#4a9eff]/20'
                        : 'border-white/10 bg-white/5 hover:bg-white/10'
                    }`}
                  >
                    <p className="text-white text-xl mb-2">Não</p>
                    <p className="text-white/60 text-sm">Sou funcionário</p>
                  </button>
                </div>
              </motion.div>
            )}

            {step === 4 && (
              <motion.div
                key="step4"
                initial={{ opacity: 0, x: 20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: -20 }}
                className="space-y-6"
              >
                <Label className="text-white text-lg mb-4 block">
                  O foco de vendas da sua loja consiste em:
                </Label>
                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                  {categories.map((category) => (
                    <motion.button
                      key={category.value}
                      onClick={() => toggleCategory(category.value)}
                      whileHover={{ scale: 1.05 }}
                      whileTap={{ scale: 0.95 }}
                      className={`p-4 rounded-xl border-2 transition-all relative ${
                        storeData.categories.includes(category.value)
                          ? 'border-[#1a4d2e] bg-[#1a4d2e]/20'
                          : 'border-white/10 bg-white/5 hover:bg-white/10'
                      }`}
                    >
                      {storeData.categories.includes(category.value) && (
                        <div className="absolute top-2 right-2 w-5 h-5 rounded-full bg-[#1a4d2e] flex items-center justify-center">
                          <Check className="w-3 h-3 text-white" />
                        </div>
                      )}
                      <div className="text-3xl mb-2">{category.icon}</div>
                      <p className="text-white text-sm">{category.label}</p>
                    </motion.button>
                  ))}
                </div>
              </motion.div>
            )}
          </AnimatePresence>

          {/* Navigation Buttons */}
          <div className="flex gap-4 mt-8">
            {step > 1 && (
              <Button
                onClick={() => setStep(step - 1)}
                variant="outline"
                className="border-white/10 text-white hover:bg-white/10"
              >
                <ArrowLeft className="w-4 h-4 mr-2" />
                Voltar
              </Button>
            )}
            <Button
              onClick={handleNext}
              disabled={createStoreMutation.isPending}
              className="flex-1 bg-gradient-to-r from-[#4a9eff] to-[#1a4d2e]"
            >
              {createStoreMutation.isPending ? 'Criando...' : step === 4 ? 'Finalizar' : 'Próximo'}
              {step < 4 && <ArrowRight className="w-4 h-4 ml-2" />}
            </Button>
          </div>
        </GlassCard>
      </motion.div>
    </div>
  );
}