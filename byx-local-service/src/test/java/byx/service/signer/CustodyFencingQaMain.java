package byx.service.signer;

import byx.service.identity.CodeIdentity;

/** Signed Service-role process probes. No real wallet lifecycle or real key operations. */
final class CustodyFencingQaMain {
    static void run(String mode) throws Exception {
        try {
            var client = new CustodyClient();
            var authority = client.authority();
            System.out.println("fencing.generation=" + authority.generation());
            System.out.println("fencing.priorResult=" + (authority.previousResultUnknown() ? "UNKNOWN" : "NONE"));
            System.out.println("fencing.authority=QUIESCENT");
            System.out.flush();
            if (mode.equals("fencing-lock")) { System.in.read(); return; }
            client.observeChild(pid -> {
                if (mode.equals("fencing-stop") || mode.equals("fencing-timeout")) {
                    var identity = CodeIdentity.load();
                    if (identity.signalInstance(identity.instance(pid), 17) != 0) { throw new IllegalStateException("STOP_FAILED"); }
                }
                System.out.println("fencing.child=" + pid);
                System.out.flush();
            });
            String op = switch (mode) {
                case "fencing-before" -> "probeBeforeMutation";
                case "fencing-after" -> "probeAfterMutation";
                default -> "count";
            };
            if (mode.equals("fencing-before") || mode.equals("fencing-after")) {
                client.observeProbe(n -> {
                    System.out.println("fencing.probeStage=" + n.path("probeStage").asText());
                    System.out.println("fencing.publicMutationCount=" + n.path("publicMutationCount").asInt(-1));
                    System.out.println("fencing.keychainCalls=" + n.path("keychainCalls").asInt(-1));
                    System.out.flush();
                });
            }
            try { System.out.println("fencing.result=" + client.call(op, "", null).status()); }
            catch (CustodyClient.CustodyException e) { System.out.println("fencing.result=" + e.code()); }
            if (mode.equals("fencing-timeout")) {
                client.observeChild(pid -> { });
                System.out.println("fencing.followup=" + client.call("count", "", null).status());
            }
        } catch (CustodyClient.CustodyException e) {
            System.out.println("fencing.result=" + e.code());
        }
        System.out.flush();
    }
}
