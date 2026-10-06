package byx.service.secrets;

/**
 * Namespace (atributo kSecAttrService) dos itens. O canário usa um namespace de TESTE com TLD reservado ({@code invalid}, RFC 2606: nunca
 * é um domínio real), claramente separado de qualquer identidade de produto. Os segredos de produção NÃO têm namespace: criá-los sob o
 * Bundle ID provisório prenderia armazenamento permanente a uma identidade provisória. Ao escolher o ID final (reverse-DNS de um domínio
 * controlado), o namespace de produção entra aqui, derivado do ID central, e só então {@link SecretId#usable()} dos reais passa a true.
 */
final class SecretNamespace {
    static final String TEST = "invalid.byx-canary-test";
    static final String ACCOUNT = "byx";

    private SecretNamespace() {
    }

    static String service(SecretId id) throws SecretStoreException {
        if (!id.usable()) {
            throw new SecretStoreException(SecretStatus.NOT_CONFIGURED); // FINAL BUNDLE ID REQUIRED
        }
        return TEST + "/" + id.wireName();
    }
}
