import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Send, ArrowLeft, User } from 'lucide-react';
import GlassCard from '../ui/GlassCard';
import moment from 'moment';
import 'moment/locale/pt-br';

moment.locale('pt-br');

export default function ChatWindow({ 
  conversation, 
  messages = [], 
  currentUserEmail, 
  onSendMessage, 
  onBack,
  isSending 
}) {
  const [newMessage, setNewMessage] = useState('');
  const messagesEndRef = useRef(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const handleSend = (e) => {
    e.preventDefault();
    if (!newMessage.trim()) return;
    onSendMessage(newMessage);
    setNewMessage('');
  };

  const isBuyer = conversation.buyer_email === currentUserEmail;
  const otherPartyEmail = isBuyer ? conversation.seller_email : conversation.buyer_email;

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <GlassCard className="p-4 mb-4">
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            onClick={onBack}
            className="text-white hover:bg-white/10"
          >
            <ArrowLeft className="w-5 h-5" />
          </Button>
          <div className="w-10 h-10 rounded-full bg-gradient-to-br from-emerald-400 to-cyan-400 flex items-center justify-center">
            <User className="w-5 h-5 text-white" />
          </div>
          <div className="flex-1">
            <p className="text-white font-medium">{otherPartyEmail}</p>
            <p className="text-white/40 text-xs">
              {isBuyer ? 'Vendedor' : 'Comprador'}
            </p>
          </div>
        </div>
      </GlassCard>

      {/* Messages */}
      <GlassCard className="flex-1 p-4 mb-4 overflow-y-auto">
        <div className="space-y-4">
          <AnimatePresence>
            {messages.length === 0 ? (
              <div className="text-center py-8 text-white/40">
                Nenhuma mensagem ainda. Inicie a conversa!
              </div>
            ) : (
              messages.map((message, index) => {
                const isOwnMessage = message.sender_email === currentUserEmail;
                return (
                  <motion.div
                    key={message.id}
                    initial={{ opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ duration: 0.2 }}
                    className={`flex ${isOwnMessage ? 'justify-end' : 'justify-start'}`}
                  >
                    <div
                      className={`max-w-[70%] rounded-2xl px-4 py-2 ${
                        isOwnMessage
                          ? 'bg-gradient-to-r from-emerald-500 to-cyan-500 text-white'
                          : 'bg-white/10 text-white'
                      }`}
                    >
                      <p className="text-sm leading-relaxed break-words">
                        {message.message}
                      </p>
                      <p
                        className={`text-xs mt-1 ${
                          isOwnMessage ? 'text-white/70' : 'text-white/40'
                        }`}
                      >
                        {moment(message.created_date).format('HH:mm')}
                      </p>
                    </div>
                  </motion.div>
                );
              })
            )}
          </AnimatePresence>
          <div ref={messagesEndRef} />
        </div>
      </GlassCard>

      {/* Input */}
      <form onSubmit={handleSend} className="flex gap-3">
        <Input
          value={newMessage}
          onChange={(e) => setNewMessage(e.target.value)}
          placeholder="Digite sua mensagem..."
          className="flex-1 bg-white/5 border-white/10 text-white placeholder:text-white/40"
          disabled={isSending}
        />
        <Button
          type="submit"
          disabled={!newMessage.trim() || isSending}
          className="bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600"
        >
          <Send className="w-5 h-5" />
        </Button>
      </form>
    </div>
  );
}