import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion, AnimatePresence } from 'framer-motion';
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { 
  Store, 
  Plus, 
  Package, 
  Trash2,
  Eye,
  Upload
} from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { toast } from 'sonner';
import CreateStoreFlow from '@/apps/site_legacy/Components/store/CreateStoreFlow';
import SalesMetrics from '@/apps/site_legacy/Components/store/SalesMetrics';
import QRPayments from '@/apps/site_legacy/Components/store/QRPayments';

const productCategories = [
  { value: 'celulares', label: 'Celulares' },
  { value: 'notebooks', label: 'Notebooks' },
  { value: 'tablets', label: 'Tablets' },
  { value: 'games', label: 'Games' },
  { value: 'acessorios', label: 'Acessórios' },
  { value: 'audio', label: 'Áudio' },
  { value: 'cameras', label: 'Câmeras' },
  { value: 'smartwatch', label: 'Smartwatch' },
  { value: 'outros', label: 'Outros' },
];

const conditions = [
  { value: 'novo', label: 'Novo' },
  { value: 'seminovo', label: 'Seminovo' },
  { value: 'usado', label: 'Usado' },
];

export default function MyStore() {
  const [showStoreFlow, setShowStoreFlow] = useState(false);
  const [showProductForm, setShowProductForm] = useState(false);
  const [productForm, setProductForm] = useState({
    name: '',
    description: '',
    price_byx: '',
    category: 'celulares',
    condition: 'novo',
  });
  const [isUploadingBanner, setIsUploadingBanner] = useState(false);

  const queryClient = useQueryClient();

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => base44.auth.me(),
  });

  const { data: stores = [] } = useQuery({
    queryKey: ['myStore', user?.email],
    queryFn: () => base44.entities.Store.filter({ owner_email: user?.email }),
    enabled: !!user?.email,
  });

  const { data: products = [] } = useQuery({
    queryKey: ['myProducts', user?.email],
    queryFn: () => base44.entities.Product.filter({ seller_email: user?.email }, '-created_date'),
    enabled: !!user?.email,
  });

  const myStore = stores[0];

  const updateUserMutation = useMutation({
    mutationFn: (data) => base44.auth.updateMe(data),
    onSuccess: () => {
      toast.success('Banner atualizado!');
      queryClient.invalidateQueries({ queryKey: ['currentUser'] });
    },
  });

  const createProductMutation = useMutation({
    mutationFn: (data) => base44.entities.Product.create({
      ...data,
      price_brl: parseFloat(data.price_byx) * 0.95,
      seller_email: user?.email,
      store_id: myStore?.id,
      status: 'available',
      views: 0,
    }),
    onSuccess: () => {
      toast.success('Produto cadastrado!');
      queryClient.invalidateQueries({ queryKey: ['myProducts'] });
      setShowProductForm(false);
      setProductForm({
        name: '',
        description: '',
        price_byx: '',
        category: 'celulares',
        condition: 'novo',
      });
    },
  });

  const toggleProductStatusMutation = useMutation({
    mutationFn: ({ id, status }) => base44.entities.Product.update(id, { status }),
    onSuccess: () => {
      toast.success('Status atualizado!');
      queryClient.invalidateQueries({ queryKey: ['myProducts'] });
    },
  });

  const deleteProductMutation = useMutation({
    mutationFn: (id) => base44.entities.Product.delete(id),
    onSuccess: () => {
      toast.success('Produto removido!');
      queryClient.invalidateQueries({ queryKey: ['myProducts'] });
    },
  });

  const handleBannerUpload = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    
    setIsUploadingBanner(true);
    try {
      const result = await base44.integrations.Core.UploadFile({ file });
      await updateUserMutation.mutateAsync({ banner_url: result.file_url });
    } catch (error) {
      toast.error('Erro ao carregar banner');
    }
    setIsUploadingBanner(false);
  };

  const handleCreateProduct = (e) => {
    e.preventDefault();
    if (!productForm.name || !productForm.price_byx) {
      toast.error('Preencha todos os campos obrigatórios');
      return;
    }
    createProductMutation.mutate(productForm);
  };

  const activeProducts = products.filter(p => p.status === 'available');
  const inactiveProducts = products.filter(p => p.status !== 'available');

  if (!myStore) {
    if (showStoreFlow) {
      return (
        <CreateStoreFlow 
          onComplete={() => {
            setShowStoreFlow(false);
            queryClient.invalidateQueries({ queryKey: ['myStore'] });
          }}
          onCancel={() => setShowStoreFlow(false)}
        />
      );
    }

    return (
      <div className="p-6 lg:p-8">
        <motion.div 
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          className="max-w-xl mx-auto text-center"
        >
          <GlassCard className="p-8">
            <Store className="w-16 h-16 text-emerald-400 mx-auto mb-4" />
            <h1 className="text-2xl font-bold text-white mb-2">Crie sua Loja</h1>
            <p className="text-white/50 mb-6">Comece a vender seus produtos eletrônicos no mercado AIOS</p>
            
            <Button 
              onClick={() => setShowStoreFlow(true)}
              className="bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600"
            >
              <Plus className="w-4 h-4 mr-2" />
              Criar Loja
            </Button>
          </GlassCard>
        </motion.div>
      </div>
    );
  }

  return (
    <div className="p-6 lg:p-8">
      {/* Header com Logo e Banner */}
      <div className="mb-8">
        <div className="relative h-48 rounded-2xl overflow-hidden mb-4">
          {/* Banner */}
          <div 
            className="absolute inset-0 bg-gradient-to-br from-emerald-500/20 to-cyan-500/20"
            style={{
              backgroundImage: user?.banner_url ? `url(${user.banner_url})` : 'none',
              backgroundSize: 'cover',
              backgroundPosition: 'center'
            }}
          />
          <div className="absolute inset-0 bg-black/30 backdrop-blur-sm" />
          
          {/* Upload Banner */}
          <label className="absolute top-4 right-4 cursor-pointer">
            <input
              type="file"
              accept="image/*"
              onChange={handleBannerUpload}
              className="hidden"
            />
            <div className="bg-white/10 backdrop-blur-xl border border-white/20 rounded-lg px-4 py-2 hover:bg-white/20 transition-colors">
              <Upload className="w-4 h-4 text-white inline mr-2" />
              <span className="text-white text-sm">
                {isUploadingBanner ? 'Carregando...' : 'Alterar Banner'}
              </span>
            </div>
          </label>

          {/* Logo e Nome */}
          <div className="absolute bottom-0 left-0 p-6 flex items-end gap-4">
            <div 
              className="w-24 h-24 rounded-2xl bg-gradient-to-br from-emerald-400 to-cyan-400 flex items-center justify-center text-white text-3xl font-bold border-4 border-black/50"
              style={{
                backgroundImage: myStore.logo_url ? `url(${myStore.logo_url})` : 'none',
                backgroundSize: 'cover'
              }}
            >
              {!myStore.logo_url && myStore.name?.charAt(0).toUpperCase()}
            </div>
            <div className="mb-2">
              <h1 className="text-3xl font-bold text-white mb-1">{myStore.name}</h1>
              <p className="text-white/70">{myStore.description || 'Sua loja no mercado AIOS'}</p>
            </div>
          </div>
        </div>
      </div>

      {/* Ações Rápidas */}
      <div className="space-y-6 mb-8">
        <SalesMetrics stores={stores} currentStore={myStore} />
        <QRPayments stores={stores} currentStore={myStore} />
      </div>

      {/* Produtos */}
      <GlassCard className="p-6">
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-2xl font-bold text-white">Meus Produtos</h2>
          <Button 
            onClick={() => setShowProductForm(true)}
            className="bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600"
          >
            <Plus className="w-4 h-4 mr-2" />
            Novo Produto
          </Button>
        </div>

        <Tabs defaultValue="active" className="w-full">
          <TabsList className="bg-white/5 border border-white/10 mb-6">
            <TabsTrigger value="active" className="data-[state=active]:bg-emerald-500">
              Ativos ({activeProducts.length})
            </TabsTrigger>
            <TabsTrigger value="inactive" className="data-[state=active]:bg-rose-500">
              Inativos ({inactiveProducts.length})
            </TabsTrigger>
          </TabsList>

          <TabsContent value="active">
            {activeProducts.length === 0 ? (
              <div className="text-center py-12">
                <Package className="w-16 h-16 text-white/20 mx-auto mb-4" />
                <h3 className="text-white/60 text-lg mb-2">Nenhum produto ativo</h3>
                <p className="text-white/40 text-sm mb-4">Adicione produtos para começar a vender</p>
                <Button 
                  onClick={() => setShowProductForm(true)}
                  variant="outline" 
                  className="border-white/10 text-white hover:bg-white/10"
                >
                  <Plus className="w-4 h-4 mr-2" />
                  Adicionar Produto
                </Button>
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {activeProducts.map((product) => (
                  <ProductCard 
                    key={product.id} 
                    product={product} 
                    onToggleStatus={toggleProductStatusMutation.mutate}
                    onDelete={deleteProductMutation.mutate}
                  />
                ))}
              </div>
            )}
          </TabsContent>

          <TabsContent value="inactive">
            {inactiveProducts.length === 0 ? (
              <div className="text-center py-12">
                <Package className="w-16 h-16 text-white/20 mx-auto mb-4" />
                <p className="text-white/60">Nenhum produto inativo</p>
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {inactiveProducts.map((product) => (
                  <ProductCard 
                    key={product.id} 
                    product={product} 
                    onToggleStatus={toggleProductStatusMutation.mutate}
                    onDelete={deleteProductMutation.mutate}
                  />
                ))}
              </div>
            )}
          </TabsContent>
        </Tabs>
      </GlassCard>

      {/* Product Form Dialog */}
      <Dialog open={showProductForm} onOpenChange={setShowProductForm}>
        <DialogContent className="bg-[#0d1320] border-white/10 text-white max-w-md">
          <DialogHeader>
            <DialogTitle className="text-white">Novo Produto</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleCreateProduct} className="space-y-4">
            <div>
              <Label className="text-white/60">Nome do Produto</Label>
              <Input
                value={productForm.name}
                onChange={(e) => setProductForm({ ...productForm, name: e.target.value })}
                className="bg-white/5 border-white/10 text-white"
                placeholder="Ex: iPhone 14 Pro"
              />
            </div>
            <div>
              <Label className="text-white/60">Descrição</Label>
              <Textarea
                value={productForm.description}
                onChange={(e) => setProductForm({ ...productForm, description: e.target.value })}
                className="bg-white/5 border-white/10 text-white"
                placeholder="Descreva o produto..."
              />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <Label className="text-white/60">Preço (AIOS)</Label>
                <Input
                  type="number"
                  value={productForm.price_byx}
                  onChange={(e) => setProductForm({ ...productForm, price_byx: e.target.value })}
                  className="bg-white/5 border-white/10 text-white"
                  placeholder="0.00"
                />
              </div>
              <div>
                <Label className="text-white/60">Condição</Label>
                <Select value={productForm.condition} onValueChange={(v) => setProductForm({ ...productForm, condition: v })}>
                  <SelectTrigger className="bg-white/5 border-white/10 text-white">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent className="bg-[#1a2030] border-white/10">
                    {conditions.map((c) => (
                      <SelectItem key={c.value} value={c.value} className="text-white hover:bg-white/10">
                        {c.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div>
              <Label className="text-white/60">Categoria</Label>
              <Select value={productForm.category} onValueChange={(v) => setProductForm({ ...productForm, category: v })}>
                <SelectTrigger className="bg-white/5 border-white/10 text-white">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent className="bg-[#1a2030] border-white/10">
                  {productCategories.map((cat) => (
                    <SelectItem key={cat.value} value={cat.value} className="text-white hover:bg-white/10">
                      {cat.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex gap-3 pt-4">
              <Button
                type="button"
                variant="outline"
                onClick={() => setShowProductForm(false)}
                className="flex-1 border-white/10 text-white"
              >
                Cancelar
              </Button>
              <Button
                type="submit"
                className="flex-1 bg-gradient-to-r from-emerald-500 to-cyan-500"
                disabled={createProductMutation.isPending}
              >
                {createProductMutation.isPending ? 'Salvando...' : 'Salvar'}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function ProductCard({ product, onToggleStatus, onDelete }) {
  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.95 }}
      animate={{ opacity: 1, scale: 1 }}
      className="p-4 rounded-xl bg-white/5 border border-white/10"
    >
      <div className="flex items-start justify-between mb-3">
        <div>
          <h4 className="text-white font-medium">{product.name}</h4>
          <p className="text-emerald-400 font-bold">{product.price_byx?.toLocaleString('pt-BR')} AIOS</p>
        </div>
        <Badge className={`
          ${product.status === 'available' ? 'bg-emerald-400/20 text-emerald-400' : 
            product.status === 'sold' ? 'bg-rose-400/20 text-rose-400' : 'bg-amber-400/20 text-amber-400'}
        `}>
          {product.status === 'available' ? 'Disponível' : product.status === 'sold' ? 'Vendido' : 'Reservado'}
        </Badge>
      </div>
      <p className="text-white/40 text-sm mb-3 line-clamp-2">{product.description}</p>
      <div className="flex items-center justify-between">
        <span className="text-white/30 text-xs flex items-center gap-1">
          <Eye className="w-3 h-3" /> {product.views || 0} views
        </span>
        <div className="flex gap-2">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onToggleStatus({ 
              id: product.id, 
              status: product.status === 'available' ? 'sold' : 'available' 
            })}
            className="text-amber-400 hover:text-amber-300 hover:bg-amber-400/10"
          >
            {product.status === 'available' ? 'Desativar' : 'Ativar'}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onDelete(product.id)}
            className="text-rose-400 hover:text-rose-300 hover:bg-rose-400/10"
          >
            <Trash2 className="w-4 h-4" />
          </Button>
        </div>
      </div>
    </motion.div>
  );
}