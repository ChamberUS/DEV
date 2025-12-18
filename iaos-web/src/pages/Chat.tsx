import { useState } from "react";
import { Search, Send, Paperclip, MoreVertical, Phone, Video } from "lucide-react";
import { DashboardLayout } from "@/components/layout/DashboardLayout";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { ScrollArea } from "@/components/ui/scroll-area";

const conversations = [
  {
    id: 1,
    name: "Magazine Luiza",
    lastMessage: "Obrigado pela proposta, vamos analisar.",
    time: "10:30",
    unread: 2,
    online: true,
  },
  {
    id: 2,
    name: "Americanas",
    lastMessage: "Quando podemos agendar a reunião?",
    time: "09:15",
    unread: 0,
    online: true,
  },
  {
    id: 3,
    name: "Amazon Brasil",
    lastMessage: "Os produtos chegaram em perfeito estado!",
    time: "Ontem",
    unread: 0,
    online: false,
  },
  {
    id: 4,
    name: "Casas Bahia",
    lastMessage: "Precisamos discutir os termos do contrato.",
    time: "Ontem",
    unread: 1,
    online: false,
  },
  {
    id: 5,
    name: "Mercado Livre",
    lastMessage: "Enviamos a documentação solicitada.",
    time: "12/12",
    unread: 0,
    online: true,
  },
];

const messages = [
  {
    id: 1,
    sender: "Magazine Luiza",
    content: "Olá! Gostaríamos de discutir uma nova proposta de parceria.",
    time: "09:00",
    isMe: false,
  },
  {
    id: 2,
    sender: "Eu",
    content: "Bom dia! Claro, estamos interessados em expandir nossa parceria. Quais são os pontos principais?",
    time: "09:05",
    isMe: true,
  },
  {
    id: 3,
    sender: "Magazine Luiza",
    content: "Estamos pensando em aumentar o volume de compras em 30% no próximo trimestre, com foco em eletrônicos e smartphones.",
    time: "09:15",
    isMe: false,
  },
  {
    id: 4,
    sender: "Eu",
    content: "Excelente! Podemos oferecer condições especiais para esse volume. Vou preparar uma proposta detalhada.",
    time: "09:20",
    isMe: true,
  },
  {
    id: 5,
    sender: "Magazine Luiza",
    content: "Perfeito! Aguardamos a proposta. Também gostaríamos de discutir prazos de entrega mais curtos.",
    time: "10:00",
    isMe: false,
  },
  {
    id: 6,
    sender: "Magazine Luiza",
    content: "Obrigado pela proposta, vamos analisar.",
    time: "10:30",
    isMe: false,
  },
];

export default function Chat() {
  const [selectedConversation, setSelectedConversation] = useState(conversations[0]);
  const [messageInput, setMessageInput] = useState("");
  const [searchTerm, setSearchTerm] = useState("");

  return (
    <DashboardLayout>
      <div className="bg-card rounded-xl shadow-card overflow-hidden h-[calc(100vh-140px)] animate-fade-in">
        <div className="flex h-full">
          {/* Conversations List */}
          <div className="w-80 border-r border-border flex flex-col">
            {/* Search */}
            <div className="p-4 border-b border-border">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" size={18} />
                <Input
                  placeholder="Pesquisar conversas..."
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className="pl-10"
                />
              </div>
            </div>

            {/* Conversations */}
            <ScrollArea className="flex-1">
              {conversations.map((conv) => (
                <button
                  key={conv.id}
                  onClick={() => setSelectedConversation(conv)}
                  className={cn(
                    "w-full p-4 flex items-start gap-3 hover:bg-muted/50 transition-colors text-left",
                    selectedConversation.id === conv.id && "bg-muted/50"
                  )}
                >
                  <div className="relative">
                    <div className="w-12 h-12 rounded-full bg-primary/10 flex items-center justify-center">
                      <span className="font-bold text-primary">
                        {conv.name.charAt(0)}
                      </span>
                    </div>
                    {conv.online && (
                      <span className="absolute bottom-0 right-0 w-3 h-3 bg-success rounded-full border-2 border-card" />
                    )}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between">
                      <span className="font-medium text-card-foreground truncate">
                        {conv.name}
                      </span>
                      <span className="text-xs text-muted-foreground">
                        {conv.time}
                      </span>
                    </div>
                    <div className="flex items-center justify-between mt-1">
                      <p className="text-sm text-muted-foreground truncate">
                        {conv.lastMessage}
                      </p>
                      {conv.unread > 0 && (
                        <span className="w-5 h-5 rounded-full bg-primary text-primary-foreground text-xs flex items-center justify-center">
                          {conv.unread}
                        </span>
                      )}
                    </div>
                  </div>
                </button>
              ))}
            </ScrollArea>
          </div>

          {/* Chat Area */}
          <div className="flex-1 flex flex-col">
            {/* Chat Header */}
            <div className="h-16 px-4 border-b border-border flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="relative">
                  <div className="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center">
                    <span className="font-bold text-primary">
                      {selectedConversation.name.charAt(0)}
                    </span>
                  </div>
                  {selectedConversation.online && (
                    <span className="absolute bottom-0 right-0 w-2.5 h-2.5 bg-success rounded-full border-2 border-card" />
                  )}
                </div>
                <div>
                  <p className="font-medium text-card-foreground">
                    {selectedConversation.name}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {selectedConversation.online ? "Online" : "Offline"}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <Button variant="ghost" size="icon">
                  <Phone size={18} />
                </Button>
                <Button variant="ghost" size="icon">
                  <Video size={18} />
                </Button>
                <Button variant="ghost" size="icon">
                  <MoreVertical size={18} />
                </Button>
              </div>
            </div>

            {/* Messages */}
            <ScrollArea className="flex-1 p-4">
              <div className="space-y-4">
                {messages.map((message) => (
                  <div
                    key={message.id}
                    className={cn(
                      "flex",
                      message.isMe ? "justify-end" : "justify-start"
                    )}
                  >
                    <div
                      className={cn(
                        "max-w-[70%] rounded-2xl px-4 py-2.5",
                        message.isMe
                          ? "bg-primary text-primary-foreground rounded-br-md"
                          : "bg-muted text-card-foreground rounded-bl-md"
                      )}
                    >
                      <p className="text-sm">{message.content}</p>
                      <p
                        className={cn(
                          "text-xs mt-1",
                          message.isMe
                            ? "text-primary-foreground/70"
                            : "text-muted-foreground"
                        )}
                      >
                        {message.time}
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            </ScrollArea>

            {/* Input */}
            <div className="p-4 border-t border-border">
              <div className="flex items-center gap-3">
                <Button variant="ghost" size="icon">
                  <Paperclip size={18} />
                </Button>
                <Input
                  placeholder="Digite sua mensagem..."
                  value={messageInput}
                  onChange={(e) => setMessageInput(e.target.value)}
                  className="flex-1"
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && messageInput.trim()) {
                      setMessageInput("");
                    }
                  }}
                />
                <Button size="icon" disabled={!messageInput.trim()}>
                  <Send size={18} />
                </Button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </DashboardLayout>
  );
}
