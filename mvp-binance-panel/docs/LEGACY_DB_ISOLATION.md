# V2.1I — Isolamento do banco legado (`panel.db`)

`panel.db` é a **fonte de rollback imutável**. O produto novo nunca o abre em leitura/escrita, nunca executa DDL/INSERT/UPDATE/DELETE contra ele e nunca o recria. Auth/segurança novas vivem no `byx-local-service`. Os dados não-auth que o painel ainda precisa vão para um banco de runtime novo e independente.

## Inventário real (somente leitura, `mode=ro&immutable=1`; SHA antes/depois idêntico)
`panel.db` SHA-256 `a75769d78cd65a333898831aecc34df1b165a6ab9a0ca9e9627b31ae03c7f7fc`, `user_version=0`, modo `delete` (sem WAL/SHM/journal). Manifesto redigido: `docs/qa/runtime-db-migration-plan-2026-10-06.json`.

| tabela | linhas | colunas (resumo) | PK / FK | classe | quem escrevia | quem lê hoje | decisão |
|---|---|---|---|---|---|---|---|
| `users` | 1 | id, username, email, password_hash, role, status, phone, flags, datas | PK id; username/email UNIQUE | **A** LEGACY_AUTH | (legado) nada no produto novo | `LegacyPanelDb.legacyUsernameExists` (só mascara linhas antigas); `byx-migrate audit` (contagens) | DROP (fica no legado, rollback) |
| `trusted_devices` | 1 | device_id, user_id, token_hash, datas | PK device_id; FK users | **A** | nada | `byx-migrate audit` (contagem) | DROP |
| `rate_limits` | 0 | subject, failures, … | PK subject | **A/F** | nada (o serviço tem o limitador) | nada | DROP |
| `meta` | 1 | name, value (`rl_pepper` do limitador legado) | PK name | **A** | nada | nada | DROP (nunca copiar) |
| `audit_log` | 136 | id, ts, event, actor, detail | PK id | **B** SECURITY/AUDIT HISTORY | `SecurityAuditService.record` — SEMPRE negado (`SERVER_AUTHORIZATION_REQUIRED`), nunca grava | `SecurityAuditService.recent/recentFor` via `LegacyPanelDb` (SettingsView, perfil) | FICA no legado, leitura imutável; não copiar |
| `sqlite_sequence` | 2 | name, seq | — | interna | SQLite | — | DROP |
| `verified_wallets` | 0 | user_id, address, public_key, chain_id, genesis_fingerprint, datas | PK (user_id,address,chain_id,genesis); FK users | **D** BYX DOMAIN (→ **E** futuro serviço) | `ByxWalletRepository.save/revoke` (atrás de `ServerAuthorizer`=DENY_ALL) | `ByxWalletRepository.list` | COPY/TRANSFORM (sem FK) |
| `byx_payment_intents` | 0 | id, user_id, wallet, chain, recipient, amount, status, datas | PK id; FK users | **D/E** | `ByxPaymentRepository.create/status/claim/consume` (DENY_ALL) | `get`, `receipts` | COPY/TRANSFORM (sem FK) |
| `byx_payment_receipts` | 0 | intent_id, user_id, wallet, tx_hash, height, datas | PK intent_id; FK intents | **D/E** | `consume` (DENY_ALL) | `receipts` | COPY (FK interna ao runtime) |
| `byx_gas_grants` | 0 | user_id, address, chain, granter, limites, state | PK (user_id,chain,genesis) | **D/E** | `GasGrantRepository` (DENY_ALL) | `all` | COPY |

Classe **C** (runtime corrente do painel): nenhuma tabela. Settings/estado do painel são arquivos `.properties`, não SQLite. Classe **F**: `rate_limits` (sem uso).

## Caminhos de escrita no `panel.db` antes da mudança → depois
- startup: `Database.init` executava `CREATE TABLE IF NOT EXISTS` de `users`, `trusted_devices`, `rate_limits`, `meta`, `audit_log` e os repositórios BYX criavam as suas (no-op só porque já existiam) → **removido**; o produto não emite DDL contra o legado.
- runtime normal: `SecurityAuditService.record` (INSERT em `audit_log`) → **nunca grava** (negado e sem acesso ao banco); repositórios BYX → agora operam no runtime DB.
- admin/research: nenhum. Só testes: fixtures sintéticas. Caminho morto: nenhum restante.

## Modelo de ownership
| dado | dono |
|---|---|
| contas, senha, 2º fator, sessão, elevação, dispositivos confiáveis, segredos | `byx-local-service` (`~/.byx-local-service`, Keychain) |
| carteiras/pagamentos/gás BYX (interino; domínio a migrar ao serviço depois) | `~/.mvp-binance-panel/runtime.db` (novo, schema v1) |
| histórico de auditoria legado | `panel.db` (somente leitura imutável, `LegacyPanelDb`) |
| rollback | `panel.db` + itens legados do Keychain (ROLLBACK_ONLY) |

`runtime.db`: diretório 0700, arquivo 0600 (`PrivateFiles`, recusa symlink/dono errado), `PRAGMA user_version`=1 explícito (versão mais nova falha fechado), recusa o nome `panel.db`. Não contém verificador de senha, OTP, token de sessão, segredo de provedor, credencial de dispositivo nem chave da autoridade. `user_id` é a identidade estável legada que o serviço apresenta (`legacyUserId`), **sem FK física** e sem tabela `users`.

## Leitura do legado
`LegacyPanelDb.openReadOnly`: URI `file:…?mode=ro&immutable=1` + `query_only=ON`, API fechada (`recent`, `recentFor`, `legacyUsernameExists`), sem `init`, sem DDL, sem fallback para escrita. Arquivo ausente/symlink/corrompido/sem tabela ⇒ histórico *indisponível*; o produto continua e **não recria** nada.

## Migrador de runtime (independente do `byx-migrate`; não toca a autoridade)
`panel.runtime.RuntimeMigrateMain`: `plan --source F` (somente leitura), `prepare --source F --target G` (frase `PREPARE-RUNTIME-DB`; recusa alvo existente, fonte com `-wal/-journal`, nome de runtime como fonte), `verify --source F --target G`. Lista fechada: `verified_wallets`, `byx_payment_intents`, `byx_payment_receipts`, `byx_gas_grants`. Nunca copia auth/segurança/histórico; recusa colunas de aparência sensível; relatório só com hashes, contagens e nomes. **Não foi executado em dados reais.**

## Plano para a migração real (para revisão; NÃO executado)
Todas as tabelas BYX do `panel.db` real têm **0 linhas** e wallet/payment/gas estão bloqueados (`DENY_ALL`). A migração real copiaria **zero linhas**: o `runtime.db` nasce vazio (o próprio produto o cria no primeiro start). Opções: (a) não rodar `prepare` e deixar o produto criar o `runtime.db` vazio; (b) rodar `prepare` para registrar o vínculo com o SHA da fonte. Em ambos, `panel.db` não muda. Limpeza do legado é fase futura separada.
