import React, { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import GlassCard from '@/components/ui/GlassCard';
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { toast } from 'sonner';
import { useAuth } from '@/auth/AuthContext';
import { getMerchantByEmail, isPlausibleByxAddress, normalizeByxAddress, saveMerchantForEmail } from '@/merchants/merchantStore';

const USER_PROFILE_KEY = 'aios_user_profile';

function loadProfileAddress(email) {
  try {
    const raw = window.localStorage.getItem(USER_PROFILE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === 'object' && parsed.email === email && typeof parsed.byxAddress === 'string') {
      return parsed.byxAddress;
    }
    return null;
  } catch {
    return null;
  }
}

export default function MerchantSetup() {
  const { userSession } = useAuth();
  const email = userSession?.email ?? null;
  const navigate = useNavigate();
  if (!email) return <Navigate to="/auth/login" replace />;

  const existing = useMemo(() => getMerchantByEmail(email), [email]);
  const profileAddress = useMemo(() => loadProfileAddress(email), [email]);

  const [displayName, setDisplayName] = useState(existing?.displayName ?? '');
  const [description, setDescription] = useState(existing?.description ?? '');
  const [category, setCategory] = useState(existing?.category ?? '');
  const [logoUrl, setLogoUrl] = useState(existing?.logoUrl ?? '');
  const [byxAddress, setByxAddress] = useState(existing?.byxAddress ?? profileAddress ?? '');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!existing && profileAddress && !byxAddress) setByxAddress(profileAddress);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function onSubmit(e) {
    e.preventDefault();
    if (saving) return;
    const normalizedAddress = normalizeByxAddress(byxAddress);
    if (!displayName.trim() || !category.trim() || !normalizedAddress) {
      toast.error('Preencha nome da loja, categoria e endereço BYX.');
      return;
    }
    if (!isPlausibleByxAddress(normalizedAddress)) {
      toast.error('Endereço BYX inválido. Verifique e tente novamente.');
      return;
    }

    setSaving(true);
    try {
      saveMerchantForEmail(email, {
        id: existing?.id,
        displayName,
        description,
        category,
        logoUrl,
        byxAddress: normalizedAddress,
      });
      toast.success('Loja salva com sucesso!');
      navigate('/merchant', { replace: true });
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Não foi possível salvar a loja.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="p-6 lg:p-8">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">{existing ? 'Editar loja' : 'Criar minha loja'}</h1>
          <p className="text-white/50">Perfil do lojista (persistência local por enquanto).</p>
        </div>
        <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
          <Link to="/merchant">Voltar</Link>
        </Button>
      </div>

      <GlassCard className="p-8">
        <form onSubmit={onSubmit} className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label className="text-white/70">Nome da loja</Label>
              <Input
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder="Ex: Loja AIOS"
                className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
              />
            </div>
            <div className="space-y-2">
              <Label className="text-white/70">Categoria</Label>
              <Input
                value={category}
                onChange={(e) => setCategory(e.target.value)}
                placeholder="Ex: Moda, Eletrônicos..."
                className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
              />
            </div>
          </div>

          <div className="space-y-2">
            <Label className="text-white/70">Descrição</Label>
            <Input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Breve descrição da loja"
              className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
            />
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label className="text-white/70">Logo URL (opcional)</Label>
              <Input
                value={logoUrl}
                onChange={(e) => setLogoUrl(e.target.value)}
                placeholder="https://..."
                className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
              />
            </div>
            <div className="space-y-2">
              <Label className="text-white/70">Endereço BYX</Label>
              <Input
                value={byxAddress}
                onChange={(e) => setByxAddress(e.target.value)}
                placeholder="byx1..."
                className="bg-white/5 border-white/10 text-white placeholder:text-white/30"
              />
              {profileAddress && (
                <div className="text-xs text-white/50">
                  Dica: você já consultou um endereço em /wallet: <code className="break-all">{profileAddress}</code>
                </div>
              )}
            </div>
          </div>

          <Button
            type="submit"
            disabled={saving}
            className="w-full h-12 bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-semibold"
          >
            {saving ? 'Salvando...' : 'Salvar loja'}
          </Button>
        </form>
      </GlassCard>
    </div>
  );
}
