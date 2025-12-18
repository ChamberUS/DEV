import React from 'react';
import { Link } from 'react-router-dom';

export default function Footer() {
  return (
    <footer className="bg-[#0a0a0a] border-t border-[#1a4d2e]/30 mt-20">
      <div className="max-w-[1920px] mx-auto px-8 py-12">
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-8">
          {/* Sobre Nós */}
          <div>
            <h3 className="text-white font-semibold mb-4">Sobre Nós</h3>
            <ul className="space-y-2">
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Sobre</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Blog</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Comunidade</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Alerta de Risco</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Termos</a></li>
            </ul>
          </div>

          {/* Produtos */}
          <div>
            <h3 className="text-white font-semibold mb-4">Produtos</h3>
            <ul className="space-y-2">
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Pay</a></li>
              <li>
                <a href="#" className="text-white/60 hover:text-white text-sm transition-colors inline-flex items-center gap-2">
                  NFT 
                  <span className="text-xs px-2 py-0.5 rounded-full bg-[#1a4d2e] text-white font-semibold">Novidade</span>
                </a>
              </li>
            </ul>
          </div>

          {/* Negócios */}
          <div>
            <h3 className="text-white font-semibold mb-4">Negócios</h3>
            <ul className="space-y-2">
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Aplicação B2B</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Aplicação P2P</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Comércio com IAOS</a></li>
            </ul>
          </div>

          {/* Serviços */}
          <div>
            <h3 className="text-white font-semibold mb-4">Serviços</h3>
            <ul className="space-y-2">
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Indicação</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Download</a></li>
            </ul>
          </div>

          {/* Aprender */}
          <div>
            <h3 className="text-white font-semibold mb-4">Aprender</h3>
            <ul className="space-y-2">
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Aprenda e Ganhe</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Comprar IAOS</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Como Começo?</a></li>
            </ul>
          </div>

          {/* Suporte */}
          <div>
            <h3 className="text-white font-semibold mb-4">Suporte</h3>
            <ul className="space-y-2">
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Suporte IA 24hrs</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Suporte Humano 24hrs</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Taxas</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">APIs</a></li>
              <li><a href="#" className="text-white/60 hover:text-white text-sm transition-colors">Aplicação da Lei</a></li>
            </ul>
          </div>
        </div>

        {/* Bottom Bar */}
        <div className="mt-12 pt-8 border-t border-[#1a4d2e]/30">
          <p className="text-white/40 text-sm text-center">IAOS © 2025</p>
        </div>
      </div>
    </footer>
  );
}