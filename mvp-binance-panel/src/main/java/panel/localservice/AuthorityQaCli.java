package panel.localservice;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ferramenta de QA da autoridade (bundle de TESTE; o produto não a referencia: há teste de guarda). Lê comandos de stdin (nunca de argv,
 * para que senha e token não apareçam em ps) e imprime só códigos e campos não sensíveis. Comandos: login USER ARQUIVO_CREDENCIAIS,
 * adopt TOKEN (simula token roubado), printtoken (QA), status, begin2fa, verify2fa CHALLENGE ARQUIVO_OTP, sms, verifysms ARQUIVO, elevate, enroll, devices, revokedev ID, logout, sleep MS, quit.
 */
public final class AuthorityQaCli {
    private AuthorityQaCli() {
    }

    public static void main(String[] args) throws Exception {
        Path home = LocalServiceClient.defaultHome();
        String challenge = null;
        try (AuthorityClient c = new AuthorityClient(home); BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                AuthorityGateway.Reply reply = null;
                switch (p[0]) {
                    case "login" -> {
                        // credenciais de TESTE do arquivo 0600 gerado pelo serviço de QA: {"user":{"id":..,"password":..}}
                        String json = Files.readString(Path.of(p[2]));
                        var node = new com.fasterxml.jackson.databind.json.JsonMapper().readTree(json).path(p[1]);
                        reply = c.login(p[1], node.path("password").asText().toCharArray());
                    }
                    case "adopt" -> {
                        c.adoptToken(p[1]);
                        System.out.println("adopted=" + c.hasSession());
                        continue;
                    }
                    case "printtoken" -> {
                        System.out.println("TOKEN=" + c.exportTokenForQa());
                        System.out.flush();
                        continue;
                    }
                    case "changepw" -> { // changepw USER ARQUIVO_CREDENCIAIS NOVA_SENHA (a senha atual vem do arquivo de teste)
                        var node = new com.fasterxml.jackson.databind.json.JsonMapper().readTree(Files.readString(Path.of(p[2]))).path(p[1]);
                        reply = c.changePassword(node.path("password").asText().toCharArray(), p[3].toCharArray());
                    }
                    case "status" -> reply = c.sessionStatus();
                    case "begin2fa" -> {
                        reply = c.beginSecondFactor();
                        challenge = reply.ok() ? reply.result().path("challenge").asText() : null;
                        if (challenge != null) {
                            System.out.println("CHALLENGE=" + challenge);
                        }
                    }
                    case "verify2fa" -> reply = c.verifySecondFactor("-".equals(p[1]) ? challenge : p[1], Files.readString(Path.of(p[2])).trim()); // "-" = o último desafio impresso
                    case "elevate" -> reply = c.adminElevation();
                    case "sms" -> reply = c.sendSecondFactorSms();
                    case "verifysms" -> reply = c.verifySecondFactorSms(Files.readString(Path.of(p[1])).trim());
                    case "enroll" -> reply = c.enrollTrustedDevice();
                    case "devices" -> {
                        reply = c.listTrustedDevices();
                        if (reply.ok()) {
                            System.out.println("DEVICES=" + reply.result().path("devices").asText());
                        }
                    }
                    case "revokedev" -> reply = c.revokeTrustedDevice(p[1]);
                    case "logout" -> reply = c.logout();
                    case "sleep" -> {
                        Thread.sleep(Long.parseLong(p[1]));
                        continue;
                    }
                    case "quit" -> {
                        return;
                    }
                    default -> {
                        System.out.println("RESULT unknown_command");
                        continue;
                    }
                }
                StringBuilder sb = new StringBuilder("RESULT ").append(p[0]).append(' ').append(reply.code());
                if (reply.ok() && reply.result() != null) {
                    for (String f : new String[] {"role", "elevated", "mfaRecent", "smsPending", "trustedDevice", "next"}) {
                        if (reply.result().has(f)) {
                            sb.append(' ').append(f).append('=').append(reply.result().get(f).asText());
                        }
                    }
                }
                System.out.println(sb);
                System.out.flush();
            }
        }
    }
}
