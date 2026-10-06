package byx.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.Set;

/**
 * Diretório de execução privado ({home}/run: 0700, dono = usuário atual) com o socket e o segredo de pareamento (0600).
 * Falha FECHADA: um diretório existente com dono errado, symlink ou permissão de grupo/outros é recusado, nunca "consertado" em silêncio.
 */
public final class RuntimeDir {
    public static final String SOCKET = "service.sock";
    public static final String TOKEN = "pairing.token";
    /** Limite de sockaddr_un no macOS é 104 bytes; deixa folga. */
    static final int MAX_SOCKET_PATH = 100;

    private final Path run;

    private RuntimeDir(Path run) {
        this.run = run;
    }

    public Path run() {
        return run;
    }

    public Path socket() {
        return run.resolve(SOCKET);
    }

    public Path token() {
        return run.resolve(TOKEN);
    }

    /** Erro de configuração insegura: o serviço não sobe. */
    public static final class InsecureException extends IOException {
        public InsecureException(String message) {
            super(message);
        }
    }

    public static RuntimeDir prepare(Path home) throws IOException {
        if (!home.isAbsolute()) {
            throw new InsecureException("home must be an absolute path");
        }
        privateDir(home);
        Path run = home.resolve("run");
        privateDir(run);
        RuntimeDir dir = new RuntimeDir(run);
        if (dir.socket().toString().getBytes(StandardCharsets.UTF_8).length > MAX_SOCKET_PATH) {
            throw new InsecureException("socket path too long for a unix domain socket");
        }
        return dir;
    }

    /** Cria (0700) ou valida: diretório real, do usuário atual, sem acesso de grupo/outros. */
    private static void privateDir(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        PosixFileAttributes a = Files.readAttributes(dir, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isDirectory() || a.isSymbolicLink()) {
            throw new InsecureException("runtime path is not a real directory");
        }
        if (!a.owner().equals(currentUser())) {
            throw new InsecureException("runtime directory is not owned by the current user");
        }
        Set<PosixFilePermission> p = a.permissions();
        if (p.contains(PosixFilePermission.GROUP_READ) || p.contains(PosixFilePermission.GROUP_WRITE) || p.contains(PosixFilePermission.GROUP_EXECUTE)
                || p.contains(PosixFilePermission.OTHERS_READ) || p.contains(PosixFilePermission.OTHERS_WRITE) || p.contains(PosixFilePermission.OTHERS_EXECUTE)) {
            throw new InsecureException("runtime directory is accessible to group or others");
        }
    }

    public static UserPrincipal currentUser() throws IOException {
        return java.nio.file.FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
    }

    /** Segredo novo a cada início (32 bytes aleatórios), gravado de forma atômica com 0600. Devolve os bytes. */
    public byte[] writeFreshToken() throws IOException {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        Path tmp = run.resolve(TOKEN + ".tmp");
        Files.deleteIfExists(tmp);
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (FileAlreadyExistsException e) {
            throw new InsecureException("pairing temp file already exists");
        }
        Files.write(tmp, Pairing.encode(secret).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, token(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return secret;
    }

    /** Remove um socket antigo apenas se for um socket do próprio usuário (nunca segue symlink nem apaga outro tipo de arquivo). */
    public void removeStaleSocket() throws IOException {
        Path s = socket();
        if (!Files.exists(s, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        PosixFileAttributes a = Files.readAttributes(s, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (a.isSymbolicLink() || a.isRegularFile() || a.isDirectory() || !a.owner().equals(currentUser())) {
            throw new InsecureException("socket path is occupied by something that is not our socket");
        }
        Files.delete(s);
    }

    public void restrictSocket() throws IOException {
        Files.setPosixFilePermissions(socket(), PosixFilePermissions.fromString("rw-------"));
    }

    public void cleanup() {
        try {
            Files.deleteIfExists(socket());
            Files.deleteIfExists(token());
        } catch (IOException ignored) {
            // melhor esforço no desligamento
        }
    }
}
