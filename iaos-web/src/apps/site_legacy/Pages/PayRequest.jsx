import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import GlassCard from '@/components/ui/GlassCard';
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { toast } from 'sonner';
import { QRCodeCanvas } from 'qrcode.react';
import { checkPaymentStatus, getLocalPaymentRequestById, normalizeStatus, updateLocalPaymentRequest } from '@/api/paymentsClient';
import { getMerchantById } from '@/merchants/merchantStore';
import { DENOM, DENOM_DECIMALS } from '@/config/chain';
import { formatMicro } from '@/utils/amount';
import { isKeplrInstalled } from '@/wallet/keplr';
import { sendPaymentWithKeplr, waitForTx } from '@/sdk/payments';
import { Loader2, CheckCircle2, XCircle, Clock, AlertTriangle } from 'lucide-react';

function formatDate(ms) {
  try {
    return new Date(ms).toLocaleString('pt-BR');
  } catch {
    return '-';
  }
}

function isNetworkErrorMessage(message) {
  const normalized = String(message || '').toLowerCase();
  return (
    normalized.includes('failed to fetch') ||
    normalized.includes('cors') ||
    normalized.includes('network') ||
    normalized.includes('fetch') ||
    normalized.includes('timeout')
  );
}

function classifyError(err) {
  const raw = err instanceof Error ? err.message : String(err || '');
  const normalized = raw.toLowerCase();

  if (normalized.includes('rejected') || normalized.includes('request rejected') || normalized.includes('user rejected')) {
    return { kind: 'rejected', message: 'Assinatura cancelada.' };
  }
  if (isNetworkErrorMessage(normalized)) {
    return { kind: 'network', message: 'Rede indisponível — verifique /network.' };
  }
  if (raw.trim().length === 0) return { kind: 'unknown', message: 'Não foi possível completar a ação.' };
  return { kind: 'unknown', message: raw };
}

export default function PayRequest() {
  const { requestId } = useParams();
  const [request, setRequest] = useState(() => (requestId ? getLocalPaymentRequestById(requestId) : null));
  const requestRef = useRef(request);
  const [checking, setChecking] = useState(false);
  const [confidence, setConfidence] = useState('unknown');
  const [txHash, setTxHash] = useState(null);
  const [flow, setFlow] = useState({ state: 'idle', title: 'Pronto', message: 'Você pode pagar com Keplr ou copiar a instrução.' });
  const [broadcastMethod, setBroadcastMethod] = useState(null);
  const [confirmElapsedMs, setConfirmElapsedMs] = useState(0);

  useEffect(() => {
    setRequest(requestId ? getLocalPaymentRequestById(requestId) : null);
  }, [requestId]);

  const merchant = useMemo(() => (request ? getMerchantById(request.merchantId) : null), [request]);
  const shareUrl = useMemo(() => (request ? `${window.location.origin}/pay/${request.id}` : ''), [request]);

  useEffect(() => {
    if (!request) return;
    const normalized = normalizeStatus(request);
    if (normalized.status !== request.status || normalized.expiresAt !== request.expiresAt) setRequest(normalized);
  }, [request]);

  useEffect(() => {
    requestRef.current = request;
  }, [request]);

  if (!request) {
    return (
      <div className="p-6 lg:p-8">
        <GlassCard className="p-8">
          <h1 className="text-2xl font-bold text-white mb-2">Pedido não encontrado</h1>
          <p className="text-white/60 mb-6">Este link pode ter expirado ou não existe neste dispositivo.</p>
          <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
            <Link to="/merchant">Área do lojista</Link>
          </Button>
        </GlassCard>
      </div>
    );
  }

  const amountLabel =
    request.denom === DENOM
      ? `${formatMicro(request.amount, DENOM_DECIMALS)} ${DENOM.startsWith('u') ? DENOM.slice(1).toUpperCase() : DENOM.toUpperCase()}`
      : `${request.amount} ${request.denom}`;
  const expired = request.status === 'expired' || request.expiresAt <= Date.now();
  const canPay = !expired && request.status !== 'paid';
  const instructionText = `Pagar ${amountLabel} para ${merchant?.byxAddress || '-'} (denom ${request.denom}) memo aios:${request.id}`;
  const memoNeedle = `aios:${request.id}`;

  const busy = flow.state === 'connecting' || flow.state === 'signing' || flow.state === 'broadcasting' || flow.state === 'confirming';

  useEffect(() => {
    if (!request) return;
    if (request.status === 'paid') {
      setFlow({ state: 'paid', title: 'Pago', message: 'Pagamento confirmado.' });
      return;
    }
    if (expired) {
      setFlow({ state: 'expired', title: 'Expirado', message: 'Este pedido expirou e não deve ser pago.' });
      return;
    }
    if (flow.state === 'paid' || flow.state === 'expired') {
      setFlow({ state: 'idle', title: 'Pronto', message: 'Você pode pagar com Keplr ou copiar a instrução.' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request.status, expired]);

  useEffect(() => {
    if (!txHash) return;
    if (flow.state !== 'confirming') return;

    let cancelled = false;
    setConfirmElapsedMs(0);

    waitForTx({
      txhash: txHash,
      memoNeedle,
      timeoutMs: 45000,
      intervalMs: 2000,
      onTick: (elapsed) => {
        if (!cancelled) setConfirmElapsedMs(elapsed);
      },
    })
      .then((res) => {
        if (cancelled) return;

        if (res.unsupported) {
          setFlow({
            state: 'confirming_timeout',
            title: 'Confirmação indisponível',
            message: 'A rede não suporta consulta de tx por hash. Use “Verificar pagamento” para tentar detectar via eventos.',
          });
          return;
        }

        if (res.failed) {
          setFlow({
            state: 'failed',
            title: 'Falha',
            message: res.rawLog && res.rawLog.trim() ? res.rawLog : 'Transação falhou.',
          });
          return;
        }

        if (res.confirmed) {
          const current = requestRef.current;
          const updated = updateLocalPaymentRequest({ ...(current || request), status: 'paid', paidTxHash: txHash });
          setRequest(updated);
          setConfidence('high');
          setFlow({ state: 'paid', title: 'Pago', message: 'Pagamento confirmado.' });
          return;
        }

        setFlow({
          state: 'confirming_timeout',
          title: 'Confirmação pendente',
          message: 'Tx enviada, mas a confirmação está demorando. Você pode tentar novamente ou verificar manualmente.',
        });
      })
      .catch(() => {
        if (cancelled) return;
        setFlow({
          state: 'confirming_timeout',
          title: 'Confirmação pendente',
          message: 'Tx enviada, mas a confirmação está demorando. Você pode tentar novamente ou verificar manualmente.',
        });
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [txHash, flow.state, memoNeedle, request]);

  async function copyText(value, label) {
    try {
      await navigator.clipboard.writeText(value);
      toast.success(`${label} copiado!`);
    } catch {
      toast.error('Não foi possível copiar.');
    }
  }

  async function onCheck() {
    if (checking) return;
    if (busy) return;
    if (expired) {
      toast.error('Pedido expirado.');
      return;
    }
    setChecking(true);
    try {
      const res = await checkPaymentStatus({ request, merchantAddress: merchant?.byxAddress });
      setRequest(res.updated);
      setConfidence(res.confidence);
      if (res.status === 'paid') toast.success('Pagamento detectado!');
      else if (res.status === 'expired') toast.error('Pedido expirado.');
      else toast.message('Ainda não detectado (ou sem indexação).');
    } finally {
      setChecking(false);
    }
  }

  function resetFlow() {
    if (request?.status === 'paid') {
      setFlow({ state: 'paid', title: 'Pago', message: 'Pagamento confirmado.' });
      return;
    }
    if (expired) {
      setFlow({ state: 'expired', title: 'Expirado', message: 'Este pedido expirou e não deve ser pago.' });
      return;
    }
    setFlow({ state: 'idle', title: 'Pronto', message: 'Você pode pagar com Keplr ou copiar a instrução.' });
  }

  async function onPayWithKeplr() {
    if (!canPay) return;
    if (!merchant?.byxAddress) {
      toast.error('Loja sem endereço BYX configurado.');
      return;
    }
    if (busy) return;
    if (!isKeplrInstalled()) {
      toast.error('Keplr não está instalado. Instale a extensão e tente novamente.');
      return;
    }

    setTxHash(null);
    setBroadcastMethod(null);
    setConfirmElapsedMs(0);
    setFlow({ state: 'connecting', title: 'Conectando', message: 'Conectando ao Keplr...' });

    try {
      const memo = (request.memo || memoNeedle).trim();

      const { txhash, mode } = await sendPaymentWithKeplr({
        toAddress: merchant.byxAddress,
        amountMicro: request.amount,
        denom: request.denom,
        memo,
        onStep: (step) => {
          if (step === 'connecting') {
            setFlow({ state: 'connecting', title: 'Conectando', message: 'Conectando ao Keplr...' });
          } else if (step === 'signing') {
            setFlow({ state: 'signing', title: 'Assinando', message: 'Aguardando assinatura no Keplr...' });
          } else if (step === 'broadcasting_rpc') {
            setFlow({ state: 'broadcasting', title: 'Enviando', message: 'Enviando transação via RPC...' });
          } else if (step === 'fallback_to_rest') {
            setFlow({ state: 'broadcasting', title: 'Enviando', message: 'Tentando via REST…' });
            toast.message('RPC indisponível — tentando via REST…');
          } else if (step === 'broadcasting_rest') {
            setFlow({ state: 'broadcasting', title: 'Enviando', message: 'Enviando transação via REST...' });
          }
        },
      });

      setBroadcastMethod(mode);
      setTxHash(txhash);
      setFlow({ state: 'confirming', title: 'Confirmando', message: 'Transação enviada. Aguardando confirmação...' });
    } catch (err) {
      const { kind, message } = classifyError(err);
      setFlow({ state: 'failed', title: 'Falha', message });
      if (kind === 'rejected') toast.error('Assinatura cancelada.');
      else if (kind === 'network') toast.error('Rede indisponível — verifique /network.');
      else toast.error(message);
    }
  }

  const statusIcon =
    flow.state === 'paid' ? (
      <CheckCircle2 className="h-5 w-5 text-emerald-400" />
    ) : flow.state === 'failed' ? (
      <XCircle className="h-5 w-5 text-rose-400" />
    ) : flow.state === 'expired' ? (
      <Clock className="h-5 w-5 text-rose-300" />
    ) : flow.state === 'confirming_timeout' ? (
      <AlertTriangle className="h-5 w-5 text-amber-300" />
    ) : busy ? (
      <Loader2 className="h-5 w-5 text-white/70 animate-spin" />
    ) : (
      <Clock className="h-5 w-5 text-white/50" />
    );

  const confirmSeconds = Math.floor(confirmElapsedMs / 1000);
  const confirmRemaining = Math.max(0, 45 - confirmSeconds);

  const showPayButton = request.status !== 'paid';
  const payButtonLabel =
    flow.state === 'connecting'
      ? 'Conectando...'
      : flow.state === 'signing'
        ? 'Assinando...'
        : flow.state === 'broadcasting'
          ? 'Enviando...'
          : flow.state === 'confirming'
            ? 'Confirmando...'
            : expired
              ? 'Pedido expirado'
              : 'Pagar com Keplr';

  return (
    <div className="p-6 lg:p-8">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <h1 className="text-2xl md:text-3xl font-bold text-white mb-2">Pagamento</h1>
          <p className="text-white/50">{merchant?.displayName || 'Loja'}</p>
        </div>
        <div className="flex gap-3">
          <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
            <Link to={merchant ? `/store/${merchant.id}` : '/'}>Voltar</Link>
          </Button>
          {showPayButton && (
            <Button
              type="button"
              disabled={busy || !canPay || flow.state === 'confirming_timeout'}
              onClick={onPayWithKeplr}
              className="bg-gradient-to-r from-emerald-500 to-cyan-500 hover:from-emerald-600 hover:to-cyan-600 text-white font-semibold"
            >
              {payButtonLabel}
            </Button>
          )}
          <Button type="button" disabled={checking || expired || busy} onClick={onCheck} className="bg-white/5 text-white/80 hover:bg-white/10">
            {checking ? 'Verificando...' : 'Verificar pagamento'}
          </Button>
        </div>
      </div>

      <GlassCard className="p-5 mb-4">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div className="flex items-start gap-3">
            <div className="mt-0.5">{statusIcon}</div>
            <div>
              <div className="text-white font-semibold">{flow.title}</div>
              <div className="text-sm text-white/60">{flow.message}</div>
              {flow.state === 'confirming' && txHash && (
                <div className="text-xs text-white/50 mt-1">
                  Confirmando... {confirmSeconds}s (restam ~{confirmRemaining}s)
                </div>
              )}
              {broadcastMethod === 'rest' && flow.state !== 'idle' && (
                <div className="text-xs text-white/50 mt-1">Broadcast: REST</div>
              )}
              {broadcastMethod === 'rpc' && flow.state !== 'idle' && (
                <div className="text-xs text-white/50 mt-1">Broadcast: RPC</div>
              )}
            </div>
          </div>

          <div className="flex flex-wrap gap-2 justify-end">
            {txHash && (
              <Button
                type="button"
                className="bg-white/5 text-white/80 hover:bg-white/10"
                onClick={() => copyText(txHash, 'TxHash')}
              >
                Ver no Explorer (copiar hash)
              </Button>
            )}
            {flow.state === 'confirming_timeout' && txHash && (
              <Button
                type="button"
                className="bg-white/5 text-white/80 hover:bg-white/10"
                onClick={() => setFlow({ state: 'confirming', title: 'Confirmando', message: 'Tentando confirmar novamente...' })}
              >
                Tentar novamente
              </Button>
            )}
            {flow.state === 'failed' && (
              <Button
                type="button"
                className="bg-white/5 text-white/80 hover:bg-white/10"
                onClick={() => resetFlow()}
              >
                Tentar novamente
              </Button>
            )}
            <Button asChild className="bg-white/5 text-white/80 hover:bg-white/10">
              <Link to="/network">Abrir /network</Link>
            </Button>
          </div>
        </div>
      </GlassCard>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <GlassCard className="p-6 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold text-white">Detalhes</h2>
            <Badge
              variant="outline"
              className={
                request.status === 'paid'
                  ? 'text-emerald-400 border-emerald-400/30'
                  : expired
                    ? 'text-rose-400 border-rose-400/30'
                    : 'text-amber-400 border-amber-400/30'
              }
            >
              {request.status === 'paid' ? 'Pago' : expired ? 'Expirado' : 'Pendente'}
            </Badge>
          </div>

          {expired && (
            <div className="text-sm text-rose-200 border border-rose-400/30 bg-rose-500/10 rounded-lg p-3">
              Este pedido expirou e não deve ser pago.
            </div>
          )}

          <div className="text-sm text-white/60 space-y-1">
            <div><span className="text-white/40">Valor:</span> <span className="text-white">{amountLabel}</span></div>
            <div className="break-all"><span className="text-white/40">Endereço:</span> {merchant?.byxAddress || '-'}</div>
            <div><span className="text-white/40">Criado:</span> {formatDate(request.createdAt)}</div>
            <div><span className="text-white/40">Expira:</span> {formatDate(request.expiresAt)}</div>
            <div className="break-all"><span className="text-white/40">Memo:</span> {request.memo}</div>
            {request.paidTxHash && <div className="break-all"><span className="text-white/40">TxHash:</span> {request.paidTxHash}</div>}
            <div><span className="text-white/40">Confiabilidade:</span> {confidence}</div>
            {txHash && !request.paidTxHash && <div className="break-all"><span className="text-white/40">Última tx enviada:</span> {txHash}</div>}
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-2">
            <Button type="button" className="bg-white/5 text-white/80 hover:bg-white/10" onClick={() => copyText(merchant?.byxAddress || '', 'Endereço')}>
              Copiar endereço
            </Button>
            <Button type="button" className="bg-white/5 text-white/80 hover:bg-white/10" onClick={() => copyText(amountLabel, 'Valor')}>
              Copiar valor
            </Button>
            <Button type="button" className="bg-white/5 text-white/80 hover:bg-white/10" onClick={() => copyText(instructionText, 'Instrução')}>
              Copiar instrução
            </Button>
          </div>
        </GlassCard>

        <GlassCard className="p-6 space-y-4">
          <h2 className="text-lg font-semibold text-white">QR Code</h2>
          <div className="flex items-center justify-center">
            <div className="bg-[#0a0a0a] border border-white/10 rounded-lg p-3">
              <QRCodeCanvas value={shareUrl || 'aios'} size={220} includeMargin bgColor="#0a0a0a" fgColor="#ffffff" />
            </div>
          </div>
          <div className="text-sm text-white/60">
            <h3 className="text-white/70 font-medium mb-2">Como pagar</h3>
            <ol className="list-decimal pl-5 space-y-1">
              <li>Abra sua wallet BYX.</li>
              <li>Envie o valor para o endereço do lojista.</li>
              <li>Inclua o memo (se possível): <code className="break-all">aios:{request.id}</code></li>
              <li>Clique em “Verificar pagamento” para tentar detectar via REST.</li>
            </ol>
            {!canPay && (
              <div className="mt-3 text-white/50">
                {request.status === 'paid' ? 'Pagamento já marcado como pago.' : 'Pagamento bloqueado (expirado).'}
              </div>
            )}
          </div>
        </GlassCard>
      </div>
    </div>
  );
}
