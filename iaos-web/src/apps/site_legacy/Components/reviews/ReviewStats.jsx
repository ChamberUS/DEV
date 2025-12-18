import React from 'react';
import { Star } from 'lucide-react';
import GlassCard from '../ui/GlassCard';

export default function ReviewStats({ reviews = [] }) {
  if (reviews.length === 0) {
    return (
      <GlassCard className="p-6 text-center">
        <Star className="w-12 h-12 text-white/20 mx-auto mb-2" />
        <p className="text-white/40">Sem avaliações</p>
      </GlassCard>
    );
  }

  const averageRating = reviews.reduce((acc, r) => acc + r.rating, 0) / reviews.length;
  
  const ratingDistribution = [5, 4, 3, 2, 1].map(rating => ({
    rating,
    count: reviews.filter(r => r.rating === rating).length,
    percentage: (reviews.filter(r => r.rating === rating).length / reviews.length) * 100
  }));

  return (
    <GlassCard className="p-6">
      <div className="text-center mb-6">
        <div className="text-5xl font-bold text-white mb-2">
          {averageRating.toFixed(1)}
        </div>
        <div className="flex justify-center mb-2">
          {[1, 2, 3, 4, 5].map((star) => (
            <Star
              key={star}
              className={`w-5 h-5 ${
                star <= Math.round(averageRating)
                  ? 'fill-amber-400 text-amber-400'
                  : 'text-white/20'
              }`}
            />
          ))}
        </div>
        <p className="text-white/40 text-sm">
          {reviews.length} {reviews.length === 1 ? 'avaliação' : 'avaliações'}
        </p>
      </div>

      <div className="space-y-2">
        {ratingDistribution.map(({ rating, count, percentage }) => (
          <div key={rating} className="flex items-center gap-3">
            <div className="flex items-center gap-1 w-12">
              <span className="text-white/60 text-sm">{rating}</span>
              <Star className="w-3 h-3 fill-amber-400 text-amber-400" />
            </div>
            <div className="flex-1 h-2 bg-white/5 rounded-full overflow-hidden">
              <div
                className="h-full bg-gradient-to-r from-emerald-500 to-cyan-500 rounded-full transition-all duration-500"
                style={{ width: `${percentage}%` }}
              />
            </div>
            <span className="text-white/40 text-sm w-8 text-right">{count}</span>
          </div>
        ))}
      </div>
    </GlassCard>
  );
}