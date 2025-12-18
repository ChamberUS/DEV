import React, { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { 
  Store, 
  User, 
  Upload, 
  Users, 
  DollarSign,
  ArrowRight,
  ArrowLeft,
  Check,
  X
} from 'lucide-react';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { base44 } from '@/api/base44Client';
import { toast } from 'sonner';

const GlassContainer = ({ children, className = "" }) => (
  <motion.div 
    initial={{ opacity: 0, scale: 0.95 }}
    animate={{ opacity: 1, scale: 1 }}
    exit={{ opacity: 0, scale: 0.95 }}
    className={`bg-white/5 backdrop-blur-xl border border-white/10 rounded-2xl p-8 ${className}`}
  >
    {children}
  </motion.div>
);

export default function CreateStoreFlow({ onComplete, onCancel }) {
  const [step, setStep] = useState(1);
  const [formData, setFormData] = useState({
    name: '',
    is_owner: true,
    actual_owner_name: '',
    actual_owner_email: '',
    actual_owner_phone: '',
    logo_url: '',
    description: '',
    employee_count: '1-5',
    monthly_revenue: '0-1500',
    category: 'eletronicos'
  });
  const [isUploading, setIsUploading] = useState(false);

  const handleFileUpload = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    
    setIsUploading(true);
    try {
      const result = await base44.integrations.Core.UploadFile({ file });
      setFormData({ ...formData, logo_url: result.file_url });
      toast.success('Imagem carregada!');
    } catch (error) {
      toast.error('Erro ao carregar imagem');
    }
    setIsUploading(false);
  };

  const handleSubmit = async () => {
    try {
      const user = await base44.auth.me();
      
      await base44.entities.Store.create({
        name: formData.name,
        description: formData.description || '',
        logo_url: formData.logo_url || '',
        category: formData.category,
        is_owner: formData.is_owner,
        actual_owner_name: formData.is_owner ? '' : formData.actual_owner_name,
        actual_owner_email: formData.is_owner ? '' : formData.actual_owner_email,
        actual_owner_phone: formData.is_owner ? '' : formData.actual_owner_phone,
        employee_count: formData.employee_count,
        monthly_revenue: formData.monthly_revenue,
        owner_email: user.email,
        rating: 5,
        total_sales: 0,
        is_verified: false
      });

      toast.success('Loja criada com sucesso!');
      onComplete();
    } catch (error) {
      toast.error('Erro ao criar loja');
    }
  };

  const nextStep = () => {
    if (step === 1 && !formData.name) {
      toast.error('Digite o nome da loja');
      return;
    }
    if (step === 2 && !formData.is_owner) {
      if (!formData.actual_owner_name || !formData.actual_owner_email) {
        toast.error('Preencha as informações do dono');
        return;
      }
    }
    setStep(step + 1);
  };

  const skipStep = () => {
    setStep(step + 1);
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-6 relative">
      <div className="absolute inset-0 pointer-events-none">
        <div className="absolute top-1/4 left-1/4 w-96 h-96 bg-emerald-500/10 rounded-full blur-[120px]" />
        <div className="absolute bottom-1/4 right-1/4 w-96 h-96 bg-cyan-500/10 rounded-full blur-[120px]" />
      </div>

      <div className="max-w-2xl w-full relative z-10">
        {/* Progress Bar */}
        <div className="mb-8">
          <div className="flex justify-between mb-2">
            {[1, 2, 3, 4, 5].map((s) => (
              <div
                key={s}
                className={`w-full h-2 rounded-full mx-1 transition-all ${
                  s <= step ? 'bg-gradient-to-r from-emerald-500 to-cyan-500' : 'bg-white/10'
                }`}
              />
            ))}
          </div>
          <p className="text-white/40 text-sm text-center">Etapa {step} de 5</p>
        </div>

        <AnimatePresence mode="wait">
          {/* Step 1: Nome da Loja */}
          {step === 1 && (
            <GlassContainer key="step1">
              <div className="text-center mb-6">
                <Store className="w-16 h-16 text-emerald-400 mx-auto mb-4" />
                <h2 className="text-3xl font-bold text-white mb-2">Nome da sua Loja</h2>
                <p className="text-white/50">Escolha um nome único para sua loja</p>
              </div>
              
              <Input
                value={formData.name}
                onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                placeholder="Ex: TechStore Premium"
                className="bg-white/10 border-white/20 text-white text-xl text-center h-14 mb-6"
                autoFocus
              />

              <div className="flex gap-3">
                <Button
                  onClick={onCancel}
                  variant="outline"
                  className="flex-1 border-white/20 text-white hover:bg-white/10"
                >
                  <X className="w-4 h-4 mr-2" />
                  Cancelar
                </Button>
                <Button
                  onClick={nextStep}
                  className="flex-1 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600"
                >
                  Próximo
                  <ArrowRight className="w-4 h-4 ml-2" />
                </Button>
              </div>
            </GlassContainer>
          )}

          {/* Step 2: Proprietário */}
          {step === 2 && (
            <GlassContainer key="step2">
              <div className="text-center mb-6">
                <User className="w-16 h-16 text-cyan-400 mx-auto mb-4" />
                <h2 className="text-3xl font-bold text-white mb-2">
                  Você é o dono da <span className="text-emerald-400">{formData.name}</span>?
                </h2>
                <p className="text-white/50">Ou está criando para outra pessoa?</p>
              </div>

              <div className="grid grid-cols-2 gap-4 mb-6">
                <Button
                  onClick={() => setFormData({ ...formData, is_owner: true })}
                  className={`h-24 ${
                    formData.is_owner
                      ? 'bg-gradient-to-br from-emerald-500 to-cyan-500'
                      : 'bg-white/5 hover:bg-white/10 text-white/70'
                  }`}
                >
                  <div>
                    <Check className="w-8 h-8 mx-auto mb-2" />
                    <p className="font-semibold">Sou eu</p>
                  </div>
                </Button>
                <Button
                  onClick={() => setFormData({ ...formData, is_owner: false })}
                  className={`h-24 ${
                    !formData.is_owner
                      ? 'bg-gradient-to-br from-purple-500 to-pink-500'
                      : 'bg-white/5 hover:bg-white/10 text-white/70'
                  }`}
                >
                  <div>
                    <Users className="w-8 h-8 mx-auto mb-2" />
                    <p className="font-semibold">Outra pessoa</p>
                  </div>
                </Button>
              </div>

              <AnimatePresence>
                {!formData.is_owner && (
                  <motion.div
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: 'auto' }}
                    exit={{ opacity: 0, height: 0 }}
                    className="space-y-4 mb-6"
                  >
                    <div>
                      <Label className="text-white/70">Nome do Dono</Label>
                      <Input
                        value={formData.actual_owner_name}
                        onChange={(e) => setFormData({ ...formData, actual_owner_name: e.target.value })}
                        placeholder="Nome completo"
                        className="bg-white/10 border-white/20 text-white"
                      />
                    </div>
                    <div>
                      <Label className="text-white/70">Email do Dono</Label>
                      <Input
                        type="email"
                        value={formData.actual_owner_email}
                        onChange={(e) => setFormData({ ...formData, actual_owner_email: e.target.value })}
                        placeholder="email@exemplo.com"
                        className="bg-white/10 border-white/20 text-white"
                      />
                    </div>
                    <div>
                      <Label className="text-white/70">Telefone do Dono</Label>
                      <Input
                        value={formData.actual_owner_phone}
                        onChange={(e) => setFormData({ ...formData, actual_owner_phone: e.target.value })}
                        placeholder="(00) 00000-0000"
                        className="bg-white/10 border-white/20 text-white"
                      />
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>

              <div className="flex gap-3">
                <Button
                  onClick={() => setStep(1)}
                  variant="outline"
                  className="flex-1 border-white/20 text-white hover:bg-white/10"
                >
                  <ArrowLeft className="w-4 h-4 mr-2" />
                  Voltar
                </Button>
                <Button
                  onClick={nextStep}
                  className="flex-1 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600"
                >
                  Próximo
                  <ArrowRight className="w-4 h-4 ml-2" />
                </Button>
              </div>
            </GlassContainer>
          )}

          {/* Step 3: Imagem e Descrição */}
          {step === 3 && (
            <GlassContainer key="step3">
              <div className="text-center mb-6">
                <Upload className="w-16 h-16 text-purple-400 mx-auto mb-4" />
                <h2 className="text-3xl font-bold text-white mb-2">Imagem e Descrição</h2>
                <p className="text-white/50">Personalize sua loja (opcional)</p>
              </div>

              <div className="space-y-4 mb-6">
                <div>
                  <Label className="text-white/70 mb-2 block">Logo da Loja</Label>
                  <div className="flex items-center gap-4">
                    {formData.logo_url && (
                      <img 
                        src={formData.logo_url} 
                        alt="Logo" 
                        className="w-20 h-20 rounded-xl object-cover border-2 border-emerald-400"
                      />
                    )}
                    <label className="flex-1">
                      <input
                        type="file"
                        accept="image/*"
                        onChange={handleFileUpload}
                        className="hidden"
                      />
                      <div className="flex items-center justify-center h-20 border-2 border-dashed border-white/20 rounded-xl cursor-pointer hover:border-emerald-400 transition-colors">
                        {isUploading ? (
                          <p className="text-white/50">Carregando...</p>
                        ) : (
                          <p className="text-white/50">Clique para escolher</p>
                        )}
                      </div>
                    </label>
                  </div>
                </div>

                <div>
                  <Label className="text-white/70">Descrição da Loja</Label>
                  <Textarea
                    value={formData.description}
                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                    placeholder="Conte sobre sua loja, o que você vende, seus diferenciais..."
                    className="bg-white/10 border-white/20 text-white h-32"
                  />
                </div>
              </div>

              <div className="flex gap-3">
                <Button
                  onClick={() => setStep(2)}
                  variant="outline"
                  className="flex-1 border-white/20 text-white hover:bg-white/10"
                >
                  <ArrowLeft className="w-4 h-4 mr-2" />
                  Voltar
                </Button>
                <Button
                  onClick={skipStep}
                  variant="outline"
                  className="flex-1 border-white/20 text-white/50 hover:bg-white/10"
                >
                  Pular
                </Button>
                <Button
                  onClick={nextStep}
                  className="flex-1 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600"
                >
                  Próximo
                  <ArrowRight className="w-4 h-4 ml-2" />
                </Button>
              </div>
            </GlassContainer>
          )}

          {/* Step 4: Funcionários */}
          {step === 4 && (
            <GlassContainer key="step4">
              <div className="text-center mb-6">
                <Users className="w-16 h-16 text-blue-400 mx-auto mb-4" />
                <h2 className="text-3xl font-bold text-white mb-2">Quantos Funcionários?</h2>
                <p className="text-white/50">Inclua você mesmo na contagem</p>
              </div>

              <Select 
                value={formData.employee_count} 
                onValueChange={(v) => setFormData({ ...formData, employee_count: v })}
              >
                <SelectTrigger className="bg-white/10 border-white/20 text-white h-14 text-lg mb-6">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent className="bg-[#1a2030] border-white/10">
                  <SelectItem value="1-5" className="text-white hover:bg-white/10">1 a 5 funcionários</SelectItem>
                  <SelectItem value="6-10" className="text-white hover:bg-white/10">6 a 10 funcionários</SelectItem>
                  <SelectItem value="11-20" className="text-white hover:bg-white/10">11 a 20 funcionários</SelectItem>
                  <SelectItem value="21-50" className="text-white hover:bg-white/10">21 a 50 funcionários</SelectItem>
                  <SelectItem value="50+" className="text-white hover:bg-white/10">Mais de 50 funcionários</SelectItem>
                </SelectContent>
              </Select>

              <div className="flex gap-3">
                <Button
                  onClick={() => setStep(3)}
                  variant="outline"
                  className="flex-1 border-white/20 text-white hover:bg-white/10"
                >
                  <ArrowLeft className="w-4 h-4 mr-2" />
                  Voltar
                </Button>
                <Button
                  onClick={nextStep}
                  className="flex-1 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600"
                >
                  Próximo
                  <ArrowRight className="w-4 h-4 ml-2" />
                </Button>
              </div>
            </GlassContainer>
          )}

          {/* Step 5: Renda Mensal */}
          {step === 5 && (
            <GlassContainer key="step5">
              <div className="text-center mb-6">
                <DollarSign className="w-16 h-16 text-amber-400 mx-auto mb-4" />
                <h2 className="text-3xl font-bold text-white mb-2">Renda Mensal</h2>
                <p className="text-white/50">Qual a faixa de faturamento da sua loja?</p>
              </div>

              <div className="grid grid-cols-1 gap-3 mb-6">
                {[
                  { value: '0-1500', label: 'R$ 0 a R$ 1.500', color: 'from-green-500 to-emerald-500' },
                  { value: '2000-10000', label: 'R$ 2.000 a R$ 10.000', color: 'from-blue-500 to-cyan-500' },
                  { value: '15000-30000', label: 'R$ 15.000 a R$ 30.000', color: 'from-purple-500 to-pink-500' },
                  { value: '50000-100000', label: 'R$ 50.000 a R$ 100.000', color: 'from-amber-500 to-orange-500' },
                ].map((option) => (
                  <Button
                    key={option.value}
                    onClick={() => setFormData({ ...formData, monthly_revenue: option.value })}
                    className={`h-16 text-lg ${
                      formData.monthly_revenue === option.value
                        ? `bg-gradient-to-r ${option.color}`
                        : 'bg-white/5 hover:bg-white/10 text-white/70'
                    }`}
                  >
                    {option.label}
                  </Button>
                ))}
              </div>

              <div className="flex gap-3">
                <Button
                  onClick={() => setStep(4)}
                  variant="outline"
                  className="flex-1 border-white/20 text-white hover:bg-white/10"
                >
                  <ArrowLeft className="w-4 h-4 mr-2" />
                  Voltar
                </Button>
                <Button
                  onClick={handleSubmit}
                  className="flex-1 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600"
                >
                  <Check className="w-4 h-4 mr-2" />
                  Concluir
                </Button>
              </div>
            </GlassContainer>
          )}
        </AnimatePresence>
      </div>
    </div>
  );
}