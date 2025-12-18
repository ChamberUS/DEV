import React, { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { base44 } from '@/api/base44Client';
import { motion } from 'framer-motion';
import { MessageCircle } from 'lucide-react';
import ChatList from '@/apps/site_legacy/Components/chat/ChatList';
import ChatWindow from '@/apps/site_legacy/Components/chat/ChatWindow';
import GlassCard from '@/components/ui/GlassCard';
import { toast } from 'sonner';

export default function Chat() {
  const [selectedConversation, setSelectedConversation] = useState(null);
  const queryClient = useQueryClient();
  const urlParams = new URLSearchParams(window.location.search);
  const conversationId = urlParams.get('id');

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => base44.auth.me(),
  });

  const { data: conversations = [] } = useQuery({
    queryKey: ['conversations', user?.email],
    queryFn: () => base44.entities.ChatConversation.filter({
      $or: [
        { buyer_email: user?.email },
        { seller_email: user?.email }
      ],
      status: 'active'
    }, '-updated_date'),
    enabled: !!user?.email,
  });

  const { data: messages = [] } = useQuery({
    queryKey: ['messages', selectedConversation?.id],
    queryFn: () => base44.entities.ChatMessage.filter(
      { conversation_id: selectedConversation.id },
      'created_date'
    ),
    enabled: !!selectedConversation?.id,
    refetchInterval: 3000, // Refresh every 3 seconds for "real-time" effect
  });

  const sendMessageMutation = useMutation({
    mutationFn: async (messageText) => {
      const message = await base44.entities.ChatMessage.create({
        conversation_id: selectedConversation.id,
        sender_email: user?.email,
        sender_name: user?.full_name,
        message: messageText,
        is_read: false,
      });

      // Update conversation
      const isBuyer = selectedConversation.buyer_email === user?.email;
      await base44.entities.ChatConversation.update(selectedConversation.id, {
        last_message: messageText,
        last_message_time: new Date().toISOString(),
        [isBuyer ? 'unread_seller' : 'unread_buyer']: 
          (isBuyer ? selectedConversation.unread_seller : selectedConversation.unread_buyer) + 1,
      });

      return message;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['messages'] });
      queryClient.invalidateQueries({ queryKey: ['conversations'] });
    },
    onError: () => {
      toast.error('Erro ao enviar mensagem');
    },
  });

  // Mark messages as read
  useEffect(() => {
    if (selectedConversation && messages.length > 0) {
      const isBuyer = selectedConversation.buyer_email === user?.email;
      const unreadCount = isBuyer ? selectedConversation.unread_buyer : selectedConversation.unread_seller;
      
      if (unreadCount > 0) {
        base44.entities.ChatConversation.update(selectedConversation.id, {
          [isBuyer ? 'unread_buyer' : 'unread_seller']: 0,
        }).then(() => {
          queryClient.invalidateQueries({ queryKey: ['conversations'] });
        });
      }
    }
  }, [selectedConversation, messages, user?.email]);

  // Auto-select conversation from URL
  useEffect(() => {
    if (conversationId && conversations.length > 0 && !selectedConversation) {
      const conv = conversations.find(c => c.id === conversationId);
      if (conv) {
        setSelectedConversation(conv);
      }
    }
  }, [conversationId, conversations, selectedConversation]);

  return (
    <div className="p-6 lg:p-8">
      {/* Header */}
      <motion.div 
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="mb-8"
      >
        <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Mensagens</h1>
        <p className="text-white/50">Converse com compradores e vendedores</p>
      </motion.div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 h-[calc(100vh-240px)]">
        {/* Conversations List */}
        <div className={`${selectedConversation ? 'hidden lg:block' : 'block'}`}>
          <GlassCard className="p-4 h-full overflow-y-auto">
            <h2 className="text-white font-semibold mb-4">Conversas</h2>
            <ChatList
              conversations={conversations}
              currentUserEmail={user?.email}
              onSelectConversation={setSelectedConversation}
            />
          </GlassCard>
        </div>

        {/* Chat Window */}
        <div className={`lg:col-span-2 ${selectedConversation ? 'block' : 'hidden lg:block'}`}>
          {selectedConversation ? (
            <ChatWindow
              conversation={selectedConversation}
              messages={messages}
              currentUserEmail={user?.email}
              onSendMessage={(msg) => sendMessageMutation.mutate(msg)}
              onBack={() => setSelectedConversation(null)}
              isSending={sendMessageMutation.isPending}
            />
          ) : (
            <GlassCard className="p-8 h-full flex items-center justify-center">
              <div className="text-center">
                <MessageCircle className="w-16 h-16 text-white/20 mx-auto mb-4" />
                <h3 className="text-white/60 text-lg mb-2">Selecione uma conversa</h3>
                <p className="text-white/40 text-sm">
                  Escolha uma conversa na lista para começar a trocar mensagens
                </p>
              </div>
            </GlassCard>
          )}
        </div>
      </div>
    </div>
  );
}