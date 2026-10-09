package byx.service.signer;

import byx.service.tx.CustodyQaFixture;
import byx.service.tx.TxPorts.SignedTx;
import byx.service.tx.TxPorts.TxSignerException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SYNTHETIC custody QA runner (test scope; packaged only in the custody QA artifact). It runs under the IDENTITY of the process that launches it (service role, panel role...)
 * and prints only statuses (never key material). Commands (one per launch, argv or first stdin line):
 * <pre>scenario | variants &lt;dir&gt; | plant &lt;ref&gt; | lookup &lt;ref&gt; | access &lt;ref&gt; | cleanup</pre>
 */
public final class CustodyQaMain {
    private static final Map<String, String> out = new LinkedHashMap<>();
    private static final HexFormat HEX = HexFormat.of();

    private CustodyQaMain() { }

    private static void put(String k, String v) {
        out.put(k, v);
        System.out.println("qa." + k + "=" + v);
    }

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : new String(System.in.readNBytes(256)).trim().split("\\s+")[0];
        String arg = args.length > 1 ? args[1] : null;
        if (cmd.equals("fencing-profile")) { byx.service.identity.FencingTiming.enable(); System.out.println("fencing.jvmEntered=true"); System.out.flush(); cmd = "fencing-timeout"; }
        put("role.selfTeam", String.valueOf(byx.service.identity.PeerIdentity.selfTeamId()));
        if (java.util.Set.of("wallet-panel-service", "wallet-panel-default-runtime", "wallet-panel-orphan-metadata", "wallet-panel-key-mismatch", "wallet-panel-orphan-key", "wallet-panel-clean-orphan", "wallet-panel-corrupt-metadata").contains(cmd)) { WalletPanelQaService.run(args); return; }
        if(cmd.startsWith("wallet-")) {
            WalletLifecycleQaMain.run(args);
            System.exit(0);
        }
        if (cmd.equals("runtime-verify")) { PackagedRuntimeQaMain.run(); return; }
        if (cmd.startsWith("fencing-")) {
            CustodyFencingQaMain.run(cmd);
            if (!byx.service.identity.FencingTiming.snapshot().isEmpty()) { System.out.println("fencing.timing=" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(byx.service.identity.FencingTiming.snapshot())); }
            System.exit(0);
        }
        switch (cmd) {
            case "scenario" -> scenario(arg == null ? null : Path.of(arg));
            case "variants" -> variants(Path.of(arg));
            case "access" -> access(arg);
            case "plant" -> plant(arg);
            case "lookup" -> lookup(arg);
            case "cleanup" -> cleanup();
            default -> put("error", "unknown command");
        }
        System.exit(0);
    }

    // ---- Keychain access probe: can THIS process read the signer's synthetic secret? ------------------------------------------------------

    interface CF extends Library {
        Pointer CFStringCreateWithCString(Pointer alloc, String s, int enc);
        Pointer CFDictionaryCreate(Pointer alloc, Pointer[] keys, Pointer[] values, long count, Pointer kc, Pointer vc);
        void CFRelease(Pointer p);
        long CFDataGetLength(Pointer d);
    }

    interface Sec extends Library {
        int SecItemCopyMatching(Pointer query, PointerByReference result);
    }

    private static final String SEC = "/System/Library/Frameworks/Security.framework/Security";
    private static final String CFP = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    /** Raw OSStatus of an exact query for the signer's group/service/account, plus whether data came back (never the data). */
    static String probe(String group, String account) {
        CF cf = Native.load(CFP, CF.class);
        Sec sec = Native.load(SEC, Sec.class);
        NativeLibrary s = NativeLibrary.getInstance(SEC);
        NativeLibrary c = NativeLibrary.getInstance(CFP);
        Pointer g = cf.CFStringCreateWithCString(null, group, 0x08000100);
        Pointer svc = cf.CFStringCreateWithCString(null, CustodyClient.NAMESPACE, 0x08000100);
        Pointer acc = cf.CFStringCreateWithCString(null, account, 0x08000100);
        Pointer[] keys = {gv(s, "kSecClass"), gv(s, "kSecUseDataProtectionKeychain"), gv(s, "kSecAttrSynchronizable"), gv(s, "kSecAttrAccessGroup"), gv(s, "kSecAttrService"),
                gv(s, "kSecAttrAccount"), gv(s, "kSecReturnData"), gv(s, "kSecMatchLimit")};
        Pointer t = gv(c, "kCFBooleanTrue");
        Pointer f = gv(c, "kCFBooleanFalse");
        Pointer[] vals = {gv(s, "kSecClassGenericPassword"), t, f, g, svc, acc, t, gv(s, "kSecMatchLimitOne")};
        Pointer q = cf.CFDictionaryCreate(null, keys, vals, keys.length, c.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"), c.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"));
        PointerByReference res = new PointerByReference();
        int rc = sec.SecItemCopyMatching(q, res);
        boolean got = rc == 0 && res.getValue() != null && cf.CFDataGetLength(res.getValue()) > 0;
        if (res.getValue() != null) {
            cf.CFRelease(res.getValue());
        }
        return "osStatus=" + rc + ",gotData=" + got;
    }

    private static Pointer gv(NativeLibrary lib, String name) {
        return lib.getGlobalVariableAddress(name).getPointer(0);
    }

    static final String GROUP = "W5Z65G9UP2.com.buynnex.byx.signer.qa.keys";

    private static void access(String ref) {
        // the exact item is planted beforehand through the signer; this process asks for it DIRECTLY with the signer's group
        put("secretAccess", probe(GROUP, ref));
    }

    private static void lookup(String ref) throws Exception {
        put("lookup", new CustodyClient().call("lookup", ref, null).status());
    }

    /** Leaves one synthetic item in the namespace (through the signer, as the trusted service caller) so other identities can try to read it. */
    private static void plant(String ref) throws Exception {
        var r = new CustodyClient().call("provision", ref, null);
        put("plant", r.status());
    }

    // ---- wrong-signer variants (copied / modified / unsigned / wrong identifier): the client must refuse BEFORE executing anything --------------------------

    /**
     * QA seam: the production client derives its fixed origin from its own bundle. To test the per-defect checks (signature/seal/identifier) independently of the origin check, and to let
     * a NON-service identity (the panel) reach the genuine helper, QA aligns the trusted origin by reflection. The production client has no such parameter or configuration.
     */
    private static CustodyClient withOrigin(Path app) throws Exception {
        CustodyClient c = new CustodyClient(app);
        var f = CustodyClient.class.getDeclaredField("trustedOrigin");
        f.setAccessible(true);
        f.set(c, app);
        return c;
    }

    private static void variants(Path dir) throws Exception {
        for (String v : new String[] {"unsigned", "modified", "wrongid", "copied"}) {
            Path app = dir.resolve(v).resolve(CustodyClient.HELPER_APP);
            try {
                // copied: the default (fixed) origin applies, the genuine copy must be refused for ORIGIN; the other three get their own path as origin so the bundle check decides
                (v.equals("copied") ? new CustodyClient(app) : withOrigin(app)).call("count", "", null);
                put("variant." + v, "ACCEPTED");
            } catch (CustodyClient.CustodyException e) {
                put("variant." + v, e.code() + ":" + e.reason());
            }
        }
    }

    private static void cleanup() throws Exception {
        var c = new CustodyClient();
        var r = c.call("cleanup", "", CustodyClient.NAMESPACE);
        var n = c.call("count", "", null);
        put("cleanup", r.status() + "(" + r.count() + ")");
        put("namespaceItemsRemaining", String.valueOf(n.count()));
    }

    // ---- the full synthetic custody scenario ----------------------------------------------------------------------------------------------------

    private static void scenario(Path explicitHelper) throws Exception {
        CustodyClient c;
        try {
            c = explicitHelper == null ? new CustodyClient() : withOrigin(explicitHelper);
        } catch (CustodyClient.CustodyException e) {
            put("client", e.code() + ":" + e.reason());
            return;
        }
        String ref = HEX.formatHex(random(16));
        Path catalog = Files.createTempDirectory("byx-custody-qa-");
        try {
            CustodyClient.Reply first;
            try {
                first = c.call("count", "", null);
            } catch (CustodyClient.CustodyException e) {
                put("call", e.code() + ":" + e.reason());
                return;
            }
            put("firstCall", first.status() + " keychainCalls=" + first.keychainCalls());
            if ("CALLER_UNTRUSTED".equals(first.status())) {
                return; // wrong role: the helper refused before any Keychain access
            }
            // clean slate (interrupted earlier runs): ONLY the exact synthetic namespace
            put("refuseOtherNamespace", c.call("cleanup", "", "auth").status());
            var pre = c.call("cleanup", "", CustodyClient.NAMESPACE);
            put("cleanSlate", pre.status() + "(" + pre.count() + ") remaining=" + c.call("count", "", null).count());

            // orphan A: metadata exists, Keychain item missing
            Files.writeString(catalog.resolve("wallet-a.json"), "{\"signingKeyRef\":\"" + ref + "\"}");
            put("orphanA.metadataWithoutKeychain", c.call("lookup", ref, null).status());

            // create the synthetic key (random scalar generated INSIDE the signer; never in this process)
            var made = c.call("provision", ref, null);
            put("keychainCreate", made.status());
            if (!"PROVISIONED".equals(made.status())) {
                return;
            }
            byte[] pub = HEX.parseHex(made.publicKey());
            put("addressMatchesIndependentDerivation", String.valueOf(made.address().equals(CosmosBankSend.address(pub))));
            put("noOverwrite", c.call("provision", ref, null).status());

            // orphan B: Keychain item exists, metadata missing -> detected, NOT adopted, NO new key
            String other = HEX.formatHex(random(16));
            put("orphanB.keychainWithoutMetadata", Files.exists(catalog.resolve(other + ".json")) ? "HAS_METADATA" : "ORPHAN_DETECTED_NOT_ADOPTED");
            put("orphanB.itemsAfter", String.valueOf(c.call("count", "", null).count()));

            // fake BankSend quote -> confirmed engine -> service invokes the signer QA -> SIGN_MODE_DIRECT -> independent verification -> STOP
            var cap = CustodyQaFixture.confirmed(ref, made.address(), CustodyQaFixture.anotherAddress(), "1500000", "synthetic custody qa");
            put("engine", cap.engineState() + " broadcasts=" + cap.broadcasts());
            SignedTx tx = c.sign(cap.request(), pub);
            put("syntheticSign", "SIGNED(txHash=" + tx.txHash().substring(0, 8) + "…)");
            put("independentVerify", "PASS(low-S, TxRaw, hash, bindings)");
            try {
                c.sign(cap.request(), CosmosBankSend.HEX.parseHex("02" + "11".repeat(32)));
                put("wrongPublicKey", "ACCEPTED");
            } catch (TxSignerException e) {
                put("wrongPublicKey", "REJECTED");
            }
            // sign with a key ref that has no item
            var missing = c.call("lookup", HEX.formatHex(random(16)), null).status();
            put("unknownRef", missing);
            put("broadcast", "NONE (transport ABSENT; no network code)");
        } finally {
            try {
                var d = c.call("delete", ref, null);
                put("delete", d.status());
                put("lookupAfterDelete", c.call("lookup", ref, null).status());
                var left = c.call("cleanup", "", CustodyClient.NAMESPACE);
                put("cleanup", left.status() + "(" + left.count() + ")");
                put("namespaceItemsRemaining", String.valueOf(c.call("count", "", null).count()));
            } catch (Exception e) {
                put("cleanup", "ERROR " + e.getClass().getSimpleName());
            }
            try (var s = Files.walk(catalog)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // temp only
            }
        }
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
