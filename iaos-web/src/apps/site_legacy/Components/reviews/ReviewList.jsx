import React from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Star, CheckCircle, User } from 'lucide-react';
import { Badge } from "@/components/ui/badge";
import GlassCard from '../ui/GlassCard';
import moment from 'moment';
import 'moment/locale/pt-br';

moment.locale('pt-br');

export default function ReviewList({ reviews = [], showProductName = false }) {
  if (reviews.length === 0) {
    return (
      <div className="text-center py-8">
        <Star className="w-12 h-12 text-white/20 mx-auto mb-3" />
        <p className="text-white/40">Nenhuma avaliação ainda</p>
        <p className="text-white/30 text-sm">Seja o primeiro a avaliar</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <AnimatePresence>
        {reviews.map((review, index) => (
          <motion.div
            key={review.id}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.3, delay: index * 0.05 }}
          >
            <GlassCard className="p-4">
              <div className="flex items-start justify-between mb-3">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-full bg-gradient-to-br from-emerald-400 to-cyan-400 flex items-center justify-center">
                    <User className="w-5 h-5 text-white" />
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <p className="text-white font-medium">
                        {review.reviewer_name || 'Usuário'}
                      </p>
                      {review.purchase_verified && (
                        <Badge className="bg-emerald-400/20 text-emerald-400 border-emerald-400/30 text-xs">
                          <CheckCircle className="w-3 h-3 mr-1" />
                          Compra verificada
                        </Badge>
                      )}
                    </div>
                    <div className="flex items-center gap-2 mt-1">
                      <div className="flex">
                        {[1, 2, 3, 4, 5].map((star) => (
                          <Star
                            key={star}
                            className={`w-4 h-4 ${
                              star <= review.rating
                                ? 'fill-amber-400 text-amber-400'
                                : 'text-white/20'
                            }`}
                          />
                        ))}
                      </div>
                      <span className="text-white/40 text-xs">
                        {moment(review.created_date).fromNow()}
                      </span>
                    </div>
                  </div>
                </div>
              </div>

              {review.comment && (
                <p className="text-white/70 text-sm leading-relaxed">
                  {review.comment}
                </p>
              )}
            </GlassCard>
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}