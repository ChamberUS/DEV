package byx.service.auth;

import byx.service.signer.WalletCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WalletAuthorityTest {
    @TempDir Path temporary;
    private static final String ID="a".repeat(32);

    @Test void actualWriterPersistsFormatFourAndAdvancesAnchorOnlyAfterCommit() throws Exception {
        Path root=temporary.toRealPath();
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        Path file=root.resolve("wallet.bin");
        var anchor=new MemoryAnchor(); var vault=new MemoryKeyVault();
        var store=AuthorityStore.openWalletQa(file,anchor,vault);
        store.initializeWalletQa(WalletCatalog.empty(ID));
        assertEquals(4,Files.readAllBytes(file)[5]);
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
        store.mutate(s->s.withWalletCatalog(new WalletCatalog(ID,2,1,java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of())));
        assertEquals(2,anchor.read().orElseThrow().version());
        assertEquals(2,AuthorityStore.openWalletQa(file,anchor,vault).current().walletCatalog().revision());
        try(var files=Files.list(root)) { assertEquals(java.util.List.of(file),files.toList()); }
        assertEquals(AuthorityStore.Status.UNTRUSTED,AuthorityStore.open(file,anchor,vault).status());
    }

    @Test void anchorFailureBlocksPublicationAndReloadRepairsOneCommittedRevision() throws Exception {
        Path root=temporary.toRealPath(); Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        Path file=root.resolve("wallet.bin"); var anchor=new MemoryAnchor();var vault=new MemoryKeyVault();
        var store=AuthorityStore.openWalletQa(file,anchor,vault); store.initializeWalletQa(WalletCatalog.empty(ID));
        anchor.failWrites(true);
        assertThrows(AuthorityException.class,()->store.mutate(s->s));
        assertThrows(AuthorityException.class,store::current);
        anchor.failWrites(false);
        assertEquals(2,AuthorityStore.openWalletQa(file,anchor,vault).current().version());
    }

    @Test void rollbackAndSymlinkFailClosed() throws Exception {
        Path root=temporary.toRealPath(); Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        Path file=root.resolve("wallet.bin");var anchor=new MemoryAnchor();var vault=new MemoryKeyVault();
        var store=AuthorityStore.openWalletQa(file,anchor,vault);store.initializeWalletQa(WalletCatalog.empty(ID));
        byte[] prior=Files.readAllBytes(file);store.mutate(s->s);
        Files.write(file,prior);
        assertEquals("rollback",AuthorityStore.openWalletQa(file,anchor,vault).reason());
        Path link=root.resolve("link");Files.createSymbolicLink(link,root);
        var linked=AuthorityStore.openWalletQa(link.resolve("new.bin"),new MemoryAnchor(),new MemoryKeyVault());
        assertThrows(AuthorityException.class,()->linked.initializeWalletQa(WalletCatalog.empty(ID)));
        assertFalse(Files.exists(root.resolve("new.bin")));
    }

    @Test void strictCatalogRejectsUnknownVersionDuplicateAndMissingFields() throws Exception {
        String valid=WalletCatalog.empty(ID).json().toString();
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        assertThrows(Exception.class,()->WalletCatalog.parse(mapper.readTree(valid.replace("\"walletCatalogVersion\":1","\"walletCatalogVersion\":2"))));
        assertThrows(Exception.class,()->WalletCatalog.parse(mapper.readTree(valid.replace("\"quarantines\":[]", "\"extra\":[]"))));
        assertThrows(Exception.class,()->WalletCatalog.parse(mapper.readTree(valid.replace("\"revision\":1,", ""))));
        assertThrows(AuthorityCodec.FormatException.class,()->AuthorityCodec.parseState(("{\"version\":1,\"version\":2}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void actualWriterHandlesShortWritesAndCommitBoundaryOrder() throws Exception {
        Path root=temporary.toRealPath();Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        var anchor=new MemoryAnchor();var vault=new MemoryKeyVault();Path file=root.resolve("wallet.bin");
        var store=AuthorityStore.openWalletQa(file,anchor,vault);store.initializeWalletQa(WalletCatalog.empty(ID));
        var order=new java.util.ArrayList<String>();store.walletQaFault(order::add,3);
        store.mutate(s->s);
        assertEquals(java.util.List.of("afterTmpCreate","afterWrite","beforeFileSync","afterFileSync","beforeRename","afterRename","beforeDirectorySync","afterDirectorySync","beforeAnchor","afterAnchor","beforePublish","afterPublish"),order);
        assertEquals(2,AuthorityStore.openWalletQa(file,anchor,vault).current().version());
    }

    @Test void formatThreeMigrationRequiresExplicitTestOnlyAuthorization() throws Exception {
        Path root=temporary.toRealPath();Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        var anchor=new MemoryAnchor();var vault=new MemoryKeyVault();Path file=root.resolve("wallet.bin");
        var legacy=AuthorityStore.open(file,anchor,vault);legacy.initialize();
        var qa=AuthorityStore.openWalletQa(file,anchor,vault);assertNull(qa.current().walletCatalog());assertEquals(3,Files.readAllBytes(file)[5]);
        assertThrows(AuthorityException.class,()->qa.mutate(s->s.withWalletCatalog(WalletCatalog.empty(ID))));
        qa.migrateWalletQa(WalletCatalog.empty(ID));assertEquals(4,Files.readAllBytes(file)[5]);assertEquals(2,qa.current().version());
        assertThrows(AuthorityException.class,()->qa.migrateWalletQa(WalletCatalog.empty(ID)));
        assertThrows(AuthorityException.class,()->legacy.migrateWalletQa(WalletCatalog.empty(ID)));
    }

    @Test void formatFourTruncationTamperingUnknownVersionAndBadAnchorFailClosed() throws Exception {
        Path root=temporary.toRealPath();Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        var anchor=new MemoryAnchor();var vault=new MemoryKeyVault();Path file=root.resolve("wallet.bin");
        var store=AuthorityStore.openWalletQa(file,anchor,vault);store.initializeWalletQa(WalletCatalog.empty(ID));
        byte[] valid=Files.readAllBytes(file);byte[] altered=valid.clone();altered[altered.length-1]^=1;
        byte[] unknown=valid.clone();unknown[5]=99;
        for(byte[] bad:java.util.List.of(java.util.Arrays.copyOf(valid,valid.length/2),altered,unknown)) {
            Files.write(file,bad);var reopened=AuthorityStore.openWalletQa(file,anchor,vault);assertEquals(AuthorityStore.Status.UNTRUSTED,reopened.status());
            assertThrows(AuthorityException.class,reopened::current);
        }
        Files.write(file,valid);var saved=anchor.read().orElseThrow();byte[] mac=saved.headMac();mac[0]^=1;
        anchor.write(new AnchorData(saved.key(),saved.version(),mac));assertEquals("mac_invalid",AuthorityStore.openWalletQa(file,anchor,vault).reason());
    }
}
