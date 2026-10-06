package byx.service.migration;

import byx.service.auth.AuthProfile;
import byx.service.identity.AppIdentity;
import byx.service.identity.PeerIdentity;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Migrador REAL (helper assinado com a identidade do serviço; acessa o cofre do serviço e o keychain legado). Subcomandos (argumento depois da classe principal,
 * nunca opção de JVM): {@code status | audit | plan | prepare | verify | finalize}. {@code audit}, {@code plan}, {@code verify} e {@code status} só LEEM o legado;
 * {@code prepare} e {@code finalize} exigem a frase de confirmação digitada no stdin (nunca por argumento). Fonte = {@code ~/.mvp-binance-panel/panel.db} somente-leitura;
 * alvo = autoridade de PRODUÇÃO em {@code ~/.byx-local-service/authority}. Não envia OTP, não usa senha, não apaga nada legado, não abre o gate privado.
 */
public final class MigrateMain {
    private MigrateMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: status|audit|plan|prepare|verify|finalize");
            System.exit(2);
        }
        Path userHome = Path.of(System.getProperty("user.home"));
        Path serviceHome = AuthProfile.defaultServiceHome();
        AuthProfile profile = AuthProfile.production(serviceHome);
        String team = PeerIdentity.selfTeamId();
        Migrator m = new Migrator(profile, userHome.resolve(".mvp-binance-panel/panel.db"), userHome.resolve(".mvp-binance-panel/providers.properties"), userHome.resolve(".mvp-binance-panel/security.properties"),
                new LegacyKeychain(LegacyKeychain.Names.REAL, true), profile.secrets(), Clock.systemUTC(),
                team == null ? "unsigned" : AppIdentity.requirement(AppIdentity.SERVICE_ID, team));
        System.exit(run(m, args[0], new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))));
    }

    /** Executa um subcomando e imprime o relatório (só contagens, estados e códigos fixos). Devolve o código de saída. */
    static int run(Migrator m, String cmd, BufferedReader in) {
        try {
            Map<String, Object> r = switch (cmd) {
                case "status" -> m.status();
                case "audit" -> m.audit();
                case "plan" -> m.plan();
                case "verify" -> m.verify();
                case "prepare" -> {
                    System.out.println("type the confirmation phrase to PREPARE the real migration (writes the new authority and copies provider secrets; nothing legacy is changed):");
                    yield m.prepare(in.readLine());
                }
                case "finalize" -> {
                    System.out.println("type the confirmation phrase to FINALIZE the cutover (lifts the migration freeze; legacy becomes ROLLBACK_ONLY, nothing is deleted):");
                    yield m.finalizeCutover(in.readLine());
                }
                default -> throw new MigrationException("unknown_command");
            };
            print(r, "");
            System.out.println("RESULT OK " + cmd);
            return 0;
        } catch (MigrationException e) {
            System.out.println("RESULT STOP " + e.code + (e.detail.isEmpty() ? "" : " " + e.detail));
            return 1;
        } catch (java.io.IOException | RuntimeException e) {
            System.out.println("RESULT STOP internal_error");
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    static void print(Map<String, Object> r, String prefix) {
        for (Map.Entry<String, Object> e : r.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> mm) {
                print((Map<String, Object>) mm, prefix + e.getKey() + ".");
            } else {
                System.out.println(prefix + e.getKey() + "=" + e.getValue());
            }
        }
    }
}
