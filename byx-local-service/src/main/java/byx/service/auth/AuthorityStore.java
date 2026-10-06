package byx.service.auth;

import byx.service.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Armazenamento da AUTORIDADE (contas, papéis, habilitação, versão de credencial, verificadores de senha) com integridade que NÃO depende de
 * permissão de arquivo contra processo do mesmo usuário:
 * <ul>
 *   <li>o arquivo é {estado versionado + HMAC-SHA256}; a chave do MAC e a versão monotônica vivem na {@link Anchor} (cofre só do serviço);</li>
 *   <li>edição de papel/habilitação/versão de credencial, inserção de conta, troca de hash ⇒ MAC inválido ⇒ não confiável;</li>
 *   <li>ROLLBACK para um instantâneo antigo (MAC válido) ⇒ versão do arquivo &lt; versão da âncora ⇒ não confiável;</li>
 *   <li>arquivo apagado com âncora presente, âncora ausente com arquivo presente, versão à frente demais, formato inválido, symlink ⇒ não confiável;</li>
 *   <li>janela de queda: arquivo na versão v+1 e âncora em v é aceito e a âncora é reparada (o serviço escreve o arquivo ANTES da âncora);</li>
 *   <li>estado não confiável FALHA FECHADO: nenhuma operação de autoridade funciona, nada é recriado automaticamente (sem "admin padrão").</li>
 * </ul>
 * Mudanças no arquivo com o serviço em execução são detectadas na próxima leitura (carimbo de tamanho/mtime/fileKey ⇒ recarrega e reverifica).
 * Limite: quem executa CÓDIGO com a identidade do serviço lê a âncora (por isso o lançador do serviço limpa o ambiente de injeção da JVM).
 */
public final class AuthorityStore {
    public enum Status { UNINITIALIZED, TRUSTED, UNTRUSTED }

    private static final long MAX_FILE = 8L * 1024 * 1024;
    private final Path file;
    private final Anchor anchor;
    private final SecureRandom random = new SecureRandom();
    private AuthorityState state;
    private byte[] key;
    private Status status = Status.UNINITIALIZED;
    private String reason = "uninitialized";
    private Object stamp;

    private AuthorityStore(Path file, Anchor anchor) {
        this.file = file;
        this.anchor = anchor;
    }

    public static AuthorityStore open(Path file, Anchor anchor) {
        AuthorityStore s = new AuthorityStore(file, anchor);
        s.load();
        return s;
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized String reason() {
        return reason;
    }

    private void untrusted(String why) {
        status = Status.UNTRUSTED;
        reason = why;
        state = null;
        if (key != null) {
            java.util.Arrays.fill(key, (byte) 0);
        }
        key = null;
        Log.event("authority_untrusted", why);
    }

    private Object stampOf() throws IOException {
        BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return java.util.List.of(a.size(), a.lastModifiedTime().toMillis(), String.valueOf(a.fileKey()));
    }

    private void load() {
        Optional<AnchorData> a;
        try {
            a = anchor.read();
        } catch (Anchor.AnchorException e) {
            untrusted("anchor_unavailable");
            return;
        }
        boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        if (!exists && a.isEmpty()) {
            status = Status.UNINITIALIZED;
            reason = "uninitialized";
            state = null;
            return;
        }
        if (!exists) {
            untrusted("file_missing");
            return;
        }
        if (a.isEmpty()) {
            untrusted("anchor_missing");
            return;
        }
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE) {
                untrusted("file_not_regular");
                return;
            }
            Object st = stampOf();
            byte[] bytes = Files.readAllBytes(file);
            AuthorityCodec.Parsed p = AuthorityCodec.parse(bytes);
            byte[] computed = AuthorityCodec.mac(a.get().key(), AuthorityCodec.canonical(p.state()));
            if (!MessageDigest.isEqual(computed, p.declaredMac())) {
                untrusted("mac_invalid");
                return;
            }
            long v = p.state().version();
            long av = a.get().version();
            if (v < av) {
                untrusted("rollback");
                return;
            }
            if (v == av && !MessageDigest.isEqual(computed, a.get().headMac())) {
                untrusted("mac_invalid");
                return;
            }
            if (v > av + 1) {
                untrusted("version_ahead");
                return;
            }
            if (v == av + 1) { // queda entre o arquivo e a âncora: repara a âncora
                try {
                    anchor.write(new AnchorData(a.get().key(), v, computed));
                } catch (Anchor.AnchorException e) {
                    untrusted("anchor_unavailable");
                    return;
                }
            }
            key = a.get().key();
            state = p.state();
            stamp = st;
            status = Status.TRUSTED;
            reason = "ok";
        } catch (AuthorityCodec.FormatException e) {
            untrusted("format_invalid");
        } catch (IOException | RuntimeException e) {
            untrusted("io_error");
        }
    }

    /** Cria o estado inicial (versão 1, sem contas) SOMENTE se não existe arquivo nem âncora. Explícito: nunca implícito em open/current. */
    public synchronized void initialize() throws AuthorityException {
        if (status != Status.UNINITIALIZED || Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new AuthorityException("already_initialized");
        }
        try {
            if (anchor.read().isPresent()) {
                throw new AuthorityException("already_initialized");
            }
        } catch (Anchor.AnchorException e) {
            throw new AuthorityException("anchor_unavailable");
        }
        byte[] k = new byte[32];
        random.nextBytes(k);
        AuthorityState first = new AuthorityState(1, java.util.List.of());
        byte[] mac = AuthorityCodec.mac(k, AuthorityCodec.canonical(first));
        try {
            writeFileAtomic(AuthorityCodec.encodeFile(first, k));
            anchor.write(new AnchorData(k, 1, mac)); // se cair aqui: arquivo sem âncora => não confiável (recuperação manual), nunca silenciosa
        } catch (IOException e) {
            throw new AuthorityException("io_error");
        } catch (Anchor.AnchorException e) {
            throw new AuthorityException("anchor_unavailable");
        }
        load();
        if (status != Status.TRUSTED) {
            throw new AuthorityException(reason);
        }
    }

    /** Estado vigente, REVERIFICADO se o arquivo mudou desde a última leitura. Não confiável ⇒ exceção (falha fechada). */
    public synchronized AuthorityState current() throws AuthorityException {
        if (status == Status.TRUSTED) {
            try {
                if (!stampOf().equals(stamp)) {
                    load(); // alteração externa: recarrega e reverifica
                }
            } catch (IOException e) {
                untrusted("file_missing");
            }
        }
        if (status != Status.TRUSTED) {
            throw new AuthorityException(status == Status.UNINITIALIZED ? "uninitialized" : reason);
        }
        return state;
    }

    /** Aplica uma mudança AUTORIZADA (só código interno do serviço): versão+1, arquivo atômico, depois âncora. */
    public synchronized AuthorityState mutate(UnaryOperator<AuthorityState> change) throws AuthorityException {
        AuthorityState cur = current();
        AuthorityState proposed;
        try {
            proposed = change.apply(cur);
        } catch (RuntimeException e) {
            throw new AuthorityException("invalid_change"); // a mudança não é aplicada; nada é escrito
        }
        AuthorityState next = new AuthorityState(cur.version() + 1, proposed.accounts());
        byte[] canon = AuthorityCodec.canonical(next);
        byte[] mac = AuthorityCodec.mac(key, canon);
        byte[] fileBytes = AuthorityCodec.encodeFile(next, key);
        try {
            AuthorityCodec.parse(fileBytes); // invariantes (ids/usernames únicos, domínios) validados pelo mesmo parser que o carregamento usa
        } catch (AuthorityCodec.FormatException e) {
            throw new AuthorityException("invalid_state");
        }
        try {
            writeFileAtomic(fileBytes);
        } catch (IOException e) {
            untrusted("io_error");
            throw new AuthorityException("io_error");
        }
        try {
            anchor.write(new AnchorData(key, next.version(), mac));
        } catch (Anchor.AnchorException e) {
            untrusted("anchor_unavailable"); // arquivo na v+1, âncora na v: um novo open() aceita e repara
            throw new AuthorityException("anchor_unavailable");
        }
        state = next;
        try {
            stamp = stampOf();
        } catch (IOException e) {
            untrusted("io_error");
            throw new AuthorityException("io_error");
        }
        return next;
    }

    /** Chave derivada (HMAC da chave de autoridade com um rótulo) para outros estados do serviço (limitador de tentativas). Só se confiável. */
    public synchronized byte[] derivedKey(String label) throws AuthorityException {
        current();
        return AuthorityCodec.mac(key, ("derive:" + label).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void writeFileAtomic(byte[] bytes) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(bytes));
            ch.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
