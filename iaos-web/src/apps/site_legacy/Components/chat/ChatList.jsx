import React from 'react';
import { motion } from 'framer-motion';
import { Badge } from "@/components/ui/badge";
import { MessageCircle, Store, Package } from 'lucide-react';
import GlassCard from '../ui/GlassCard';
import moment from 'moment';
import 'moment/locale/pt-br';

moment.locale('pt-br');

export default function ChatList({ conversations = [], currentUserEmail, onSelectConversation }) {
  if (conversations.length === 0) {
    return (
      <div className="text-center py-12">
        <MessageCircle className="w-16 h-16 text-white/20 mx-auto mb-4" />
        <h3 className="text-white/60 text-lg mb-2">Nenhuma conversa</h3>
        <p className="text-white/40 text-sm">Suas conversas aparecerão aqui</p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {conversations.map((conversation, index) => {
        const isBuyer = conversation.buyer_email === currentUserEmail;
        const otherPartyEmail = isBuyer ? conversation.seller_email : conversation.buyer_email;
        const unreadCount = isBuyer ? conversation.unread_buyer : conversation.unread_seller;

        return (
          <motion.div
            key={conversation.id}
            initial={{ opacity: 0, x: -20 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.3, delay: index * 0.05 }}
          >
            <GlassCard
              className="p-4 cursor-pointer hover:bg-white/10 transition-all"
              onClick={() => onSelectConversation(conversation)}
            >
              <div className="flex items-start gap-3">
                <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-emerald-400 to-cyan-400 flex items-center justify-center flex-shrink-0">
                  {conversation.product_id ? (
                    <Package className="w-6 h-6 text-white" />
                  ) : (
                    <Store className="w-6 h-6 text-white" />
                  )}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-start justify-between gap-2 mb-1">
                    <p className="text-white font-medium truncate">
                      {otherPartyEmail}
                    </p>
                    {unreadCount > 0 && (
                      <Badge className="bg-emerald-400 text-white text-xs">
                        {unreadCount}
                      </Badge>
                    )}
                  </div>
                  <p className="text-white/50 text-sm truncate mb-1">
                    {conversation.last_message || 'Nenhuma mensagem ainda'}
                  </p>
                  <p className="text-white/30 text-xs">
                    {conversation.last_message_time 
                      ? moment(conversation.last_message_time).fromNow()
                      : moment(conversation.created_date).fromNow()
                    }
                  </p>
                </div>
              </div>
            </GlassCard>
          </motion.div>
        );
      })}
    </div>
  );
}