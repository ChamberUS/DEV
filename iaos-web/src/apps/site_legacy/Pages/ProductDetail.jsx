import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion } from 'framer-motion';
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { 
  ArrowLeft, 
  ShoppingCart, 
  MessageCircle, 
  Star, 
  Eye, 
  Package,
  Store as StoreIcon,
  BadgeCheck
} from 'lucide-react';
import GlassCard from '@/components/ui/GlassCard';
import ReviewStats from '@/apps/site_legacy/Components/reviews/ReviewStats';
import ReviewList from '@/apps/site_legacy/Components/reviews/ReviewList';
import ReviewForm from '@/apps/site_legacy/Components/reviews/ReviewForm';
import { useNavigate } from 'react-router-dom';
import { createPageUrl } from '@/utils/siteLegacy';
import { toast } from 'sonner';

export default function ProductDetail() {
  const [showReviewForm, setShowReviewForm] = useState(false);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const urlParams = new URLSearchParams(window.location.search);
  const productId = urlParams.get('id');

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => base44.auth.me(),
  });

  const { data: product, isLoading } = useQuery({
    queryKey: ['product', productId],
    queryFn: async () => {
      const products = await base44.entities.Product.filter({ id: productId });
      return products[0];
    },
    enabled: !!productId,
  });

  const { data: store } = useQuery({
    queryKey: ['store', product?.store_id],
    queryFn: async () => {
      const stores = await base44.entities.Store.filter({ id: product.store_id });
      return stores[0];
    },
    enabled: !!product?.store_id,
  });

  const { data: reviews = [] } = useQuery({
    queryKey: ['productReviews', productId],
    queryFn: () => base44.entities.Review.filter({ product_id: productId }, '-created_date'),
    enabled: !!productId,
  });

  const { data: orders = [] } = useQuery({
    queryKey: ['userOrders', user?.email, productId],
    queryFn: () => base44.entities.Order.filter({
      buyer_email: user?.email,
      product_id: productId,
      status: 'delivered',
      reviewed: false
    }),
    enabled: !!user?.email && !!productId,
  });

  const canReview = orders.length > 0 && orders[0].can_review;

  const createReviewMutation = useMutation({
    mutationFn: async (reviewData) => {
      const review = await base44.entities.Review.create({
        ...reviewData,
        product_id: productId,
        store_id: product.store_id,
        reviewer_email: user?.email,
        reviewer_name: user?.full_name,
        purchase_verified: true,
      });

      // Update order as reviewed
      if (orders[0]) {
        await base44.entities.Order.update(orders[0].id, { reviewed: true });
      }

      return review;
    },
    onSuccess: () => {
      toast.success('Avaliação enviada com sucesso!');
      queryClient.invalidateQueries({ queryKey: ['productReviews'] });
      queryClient.invalidateQueries({ queryKey: ['userOrders'] });
      setShowReviewForm(false);
    },
  });

  const startChatMutation = useMutation({
    mutationFn: async () => {
      // Check if conversation already exists
      const existing = await base44.entities.ChatConversation.filter({
        product_id: productId,
        buyer_email: user?.email,
        seller_email: product.seller_email,
      });

      if (existing.length > 0) {
        return existing[0];
      }

      // Create new conversation
      return await base44.entities.ChatConversation.create({
        product_id: productId,
        store_id: product.store_id,
        buyer_email: user?.email,
        seller_email: product.seller_email,
        status: 'active',
      });
    },
    onSuccess: (conversation) => {
      navigate(createPageUrl('Chat') + `?id=${conversation.id}`);
    },
  });

  const purchaseProductMutation = useMutation({
    mutationFn: async () => {
      // Create order
      const order = await base44.entities.Order.create({
        product_id: productId,
        product_name: product.name,
        store_id: product.store_id,
        buyer_email: user?.email,
        seller_email: product.seller_email,
        amount_byx: product.price_byx,
        amount_brl: product.price_brl,
        status: 'pending',
      });

      // Create transaction
      await base44.entities.Transaction.create({
        type: 'purchase',
        amount: product.price_byx,
        description: `Compra: ${product.name}`,
        from_user: user?.email,
        to_user: product.seller_email,
      });

      return order;
    },
    onSuccess: () => {
      toast.success('Compra realizada! Aguarde confirmação do vendedor.');
      queryClient.invalidateQueries({ queryKey: ['transactions'] });
    },
  });

  if (isLoading) {
    return (
      <div className="p-6 lg:p-8">
        <GlassCard className="h-96 animate-pulse" />
      </div>
    );
  }

  if (!product) {
    return (
      <div className="p-6 lg:p-8 text-center">
        <Package className="w-16 h-16 text-white/20 mx-auto mb-4" />
        <p className="text-white/60">Produto não encontrado</p>
      </div>
    );
  }

  const averageRating = reviews.length > 0
    ? reviews.reduce((acc, r) => acc + r.rating, 0) / reviews.length
    : 0;

  return (
    <div className="p-6 lg:p-8">
      {/* Back Button */}
      <Button
        variant="ghost"
        onClick={() => navigate(-1)}
        className="mb-6 text-white hover:bg-white/10"
      >
        <ArrowLeft className="w-4 h-4 mr-2" />
        Voltar
      </Button>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
        {/* Main Content */}
        <div className="lg:col-span-2 space-y-6">
          {/* Product Image & Info */}
          <GlassCard className="overflow-hidden">
            <div className="aspect-video w-full bg-gradient-to-br from-slate-700 to-slate-800 relative">
              {product.images?.[0] && (
                <img
                  src={product.images[0]}
                  alt={product.name}
                  className="w-full h-full object-cover"
                />
              )}
              <div className="absolute top-4 left-4 flex gap-2">
                <Badge className="bg-emerald-400/90 text-white backdrop-blur-sm">
                  {product.condition}
                </Badge>
                <Badge className="bg-blue-400/90 text-white backdrop-blur-sm">
                  {product.category}
                </Badge>
              </div>
            </div>

            <div className="p-6">
              <div className="flex items-start justify-between mb-4">
                <div className="flex-1">
                  <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">
                    {product.name}
                  </h1>
                  <div className="flex items-center gap-3 mb-3">
                    {averageRating > 0 && (
                      <div className="flex items-center gap-1">
                        <Star className="w-4 h-4 fill-amber-400 text-amber-400" />
                        <span className="text-white font-medium">{averageRating.toFixed(1)}</span>
                        <span className="text-white/40 text-sm">({reviews.length})</span>
                      </div>
                    )}
                    <div className="flex items-center gap-1 text-white/40">
                      <Eye className="w-4 h-4" />
                      <span className="text-sm">{product.views || 0} visualizações</span>
                    </div>
                  </div>
                </div>
              </div>

              <div className="mb-6">
                <p className="text-3xl font-bold text-emerald-400 mb-1">
                  {product.price_byx?.toLocaleString('pt-BR')} BYX
                </p>
                <p className="text-white/40">
                  ≈ R$ {product.price_brl?.toLocaleString('pt-BR', { minimumFractionDigits: 2 })}
                </p>
              </div>

              <p className="text-white/70 mb-6 leading-relaxed">
                {product.description || 'Sem descrição disponível'}
              </p>

              {/* Actions */}
              <div className="flex flex-col sm:flex-row gap-3">
                <Button
                  onClick={() => purchaseProductMutation.mutate()}
                  disabled={purchaseProductMutation.isPending || product.seller_email === user?.email}
                  className="flex-1 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 h-12"
                >
                  <ShoppingCart className="w-5 h-5 mr-2" />
                  {purchaseProductMutation.isPending ? 'Processando...' : 'Comprar agora'}
                </Button>
                <Button
                  onClick={() => startChatMutation.mutate()}
                  disabled={startChatMutation.isPending || product.seller_email === user?.email}
                  variant="outline"
                  className="border-white/10 text-white hover:bg-white/10 h-12"
                >
                  <MessageCircle className="w-5 h-5 mr-2" />
                  Conversar
                </Button>
              </div>
            </div>
          </GlassCard>

          {/* Reviews Section */}
          <GlassCard className="p-6">
            <div className="flex items-center justify-between mb-6">
              <h2 className="text-xl font-semibold text-white">Avaliações</h2>
              {canReview && !showReviewForm && (
                <Button
                  onClick={() => setShowReviewForm(true)}
                  size="sm"
                  className="bg-emerald-500 hover:bg-emerald-600"
                >
                  <Star className="w-4 h-4 mr-2" />
                  Avaliar
                </Button>
              )}
            </div>

            {showReviewForm && (
              <div className="mb-6">
                <ReviewForm
                  onSubmit={(data) => createReviewMutation.mutate(data)}
                  onCancel={() => setShowReviewForm(false)}
                  isSubmitting={createReviewMutation.isPending}
                />
              </div>
            )}

            <ReviewList reviews={reviews} />
          </GlassCard>
        </div>

        {/* Sidebar */}
        <div className="space-y-6">
          {/* Store Info */}
          {store && (
            <GlassCard className="p-6">
              <h3 className="text-white font-semibold mb-4 flex items-center gap-2">
                <StoreIcon className="w-5 h-5 text-emerald-400" />
                Informações da Loja
              </h3>
              <div className="flex items-center gap-3 mb-4">
                <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-slate-700 to-slate-800 flex items-center justify-center text-white text-xl font-bold">
                  {store.name?.charAt(0).toUpperCase()}
                </div>
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <p className="text-white font-medium">{store.name}</p>
                    {store.is_verified && (
                      <BadgeCheck className="w-4 h-4 text-emerald-400" />
                    )}
                  </div>
                  <div className="flex items-center gap-1">
                    <Star className="w-3 h-3 fill-amber-400 text-amber-400" />
                    <span className="text-white/60 text-sm">{store.rating?.toFixed(1) || '5.0'}</span>
                  </div>
                </div>
              </div>
              <p className="text-white/50 text-sm mb-4">{store.description}</p>
              <Button
                variant="outline"
                className="w-full border-white/10 text-white hover:bg-white/10"
                onClick={() => navigate(createPageUrl('Stores'))}
              >
                Ver loja completa
              </Button>
            </GlassCard>
          )}

          {/* Review Stats */}
          <ReviewStats reviews={reviews} />
        </div>
      </div>
    </div>
  );
}