package byx.service.migration;

/** Falha da migração: PARA tudo. code fixo + nomes de campos/ids (nunca valores, hashes, segredos ou texto do usuário). Nada é corrigido automaticamente. */
public final class MigrationException extends Exception {
    public final String code;
    public final String detail;

    public MigrationException(String code, String detail) {
        super(code + (detail == null || detail.isEmpty() ? "" : " " + detail), null, false, false);
        this.code = code;
        this.detail = detail == null ? "" : detail;
    }

    public MigrationException(String code) {
        this(code, "");
    }
}
