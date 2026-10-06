package byx.service.migration;

import java.util.List;
import java.util.Map;

/**
 * Leitura SOMENTE-LEITURA do banco legado: usuários (em memória), contagens de dispositivos confiáveis/auditoria e impressões digitais. Nenhum valor
 * sensível sai desta estrutura em toString.
 */
record LegacySnapshot(List<LegacyUser> users, String dbSha256, String schemaFingerprint, long userVersion, String usersDigest, Map<String, Long> trustedDeviceStates, long auditRows,
        List<String> metaNames, long dbBytes) {
    @Override
    public String toString() {
        return "LegacySnapshot[users=" + users.size() + "]";
    }
}
