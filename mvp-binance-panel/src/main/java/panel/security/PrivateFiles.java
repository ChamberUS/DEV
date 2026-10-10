package panel.security;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Política dos arquivos sensíveis do aplicativo (banco SQLite e vizinhos), POSIX/macOS. NOVO: diretório 0700 e arquivo 0600, criados já com
 * essas permissões (sem janela legível por outros). EXISTENTE: falha FECHADA se o diretório for symlink, não for diretório, for de outro
 * usuário ou tiver escrita para grupo/outros; o arquivo existente nunca é alterado por {@code open} (só {@link #tighten} faz isso, por
 * decisão explícita, e só em arquivo do próprio usuário). Bits de leitura/execução abertos em diretório existente são informados por
 * {@link #audit}, não corrigidos em silêncio. Arquivos auxiliares do SQLite (-journal, -wal, -shm) herdam o modo do banco.
 */
public final class PrivateFiles {
    private static final Set<PosixFilePermission> DIR_0700 = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_0600 = PosixFilePermissions.fromString("rw-------");

    private PrivateFiles() {
    }

    /** Armazenamento fora da política: o aplicativo não continua em silêncio. A mensagem nunca inclui caminho. */
    public static final class InsecureStorageException extends IllegalStateException {
        public final String code;

        public InsecureStorageException(String code) {
            super("Insecure local storage: " + code);
            this.code = code;
        }
    }

    private static UserPrincipal me() throws IOException {
        return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
    }

    private static boolean posix(Path p) {
        return Files.getFileAttributeView(p, PosixFileAttributeView.class) != null;
    }

    /** Prepara o diretório do banco: cria 0700 se faltar; se existir, só aceita o que passa na política. */
    public static void prepareDirectory(Path dir) throws IOException {
        if (WindowsStorage.supported()) { WindowsStorage.prepareDirectory(dir); return; }
        Path parent = dir.toAbsolutePath();
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            if (!posix(parent.getParent())) {
                throw new InsecureStorageException("not_posix");
            }
            Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(DIR_0700));
            Files.setPosixFilePermissions(parent, DIR_0700); // a umask não pode afrouxar nem apertar o combinado
        }
        PosixFileAttributes a = Files.readAttributes(parent, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (a.isSymbolicLink()) {
            throw new InsecureStorageException("directory_is_symlink");
        }
        if (!a.isDirectory()) {
            throw new InsecureStorageException("not_a_directory");
        }
        if (!a.owner().equals(me())) {
            throw new InsecureStorageException("directory_wrong_owner");
        }
        for (PosixFilePermission p : a.permissions()) {
            if (p == PosixFilePermission.GROUP_WRITE || p == PosixFilePermission.OTHERS_WRITE) {
                throw new InsecureStorageException("directory_writable_by_others");
            }
        }
    }

    /** Arquivo novo nasce 0600 (antes do SQLite tocar nele); existente deve ser arquivo regular do próprio usuário e NÃO é alterado. */
    public static void prepareFile(Path file) throws IOException {
        if (WindowsStorage.supported()) { WindowsStorage.prepareFile(file); return; }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_0600));
            Files.setPosixFilePermissions(file, FILE_0600);
            return;
        }
        PosixFileAttributes a = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (a.isSymbolicLink() || !a.isRegularFile()) {
            throw new InsecureStorageException("file_not_regular");
        }
        if (!a.owner().equals(me())) {
            throw new InsecureStorageException("file_wrong_owner");
        }
    }

    /**
     * Ação EXPLÍCITA e separada (não é chamada pelo aplicativo): aperta um arquivo existente do próprio usuário para 0600. Recusa symlink,
     * não-regular e arquivo de outro dono. Não toca nenhum outro arquivo.
     */
    public static void tighten(Path file) throws IOException {
        if (WindowsStorage.supported()) {
            throw new InsecureStorageException("windows_explicit_acl_migration_required");
        }
        prepareFile(file);
        Files.setPosixFilePermissions(file, FILE_0600);
    }

    /** Leitura somente de metadados: o que está fora da política (nada é alterado). Cada item é um código fixo. */
    public static List<String> audit(Path dir, Path db) {
        if (WindowsStorage.supported()) return WindowsStorage.audit(dir, db);
        List<String> findings = new ArrayList<>();
        try {
            PosixFileAttributes d = Files.readAttributes(dir, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (d.isSymbolicLink()) {
                findings.add("directory_is_symlink");
            }
            if (!d.owner().equals(me())) {
                findings.add("directory_wrong_owner");
            }
            for (PosixFilePermission p : d.permissions()) {
                switch (p) {
                    case GROUP_WRITE, OTHERS_WRITE -> findings.add("directory_writable_by_others");
                    case GROUP_READ, GROUP_EXECUTE, OTHERS_READ, OTHERS_EXECUTE -> findings.add("directory_open_bits");
                    default -> { }
                }
            }
            for (String suffix : new String[] {"", "-journal", "-wal", "-shm"}) {
                Path f = db.resolveSibling(db.getFileName() + suffix);
                if (Files.exists(f, LinkOption.NOFOLLOW_LINKS)) {
                    PosixFileAttributes a = Files.readAttributes(f, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    boolean loose = a.permissions().stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"));
                    if (loose) {
                        findings.add("file_open_bits" + suffix);
                    }
                    if (!a.owner().equals(me())) {
                        findings.add("file_wrong_owner" + suffix);
                    }
                }
            }
        } catch (IOException e) {
            findings.add("unreadable");
        }
        return findings.stream().distinct().toList();
    }
}
