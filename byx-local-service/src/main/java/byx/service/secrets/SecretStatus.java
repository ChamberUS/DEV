package byx.service.secrets;

/** Estados que a UI pode conhecer. Nunca carregam segredo, consulta bruta do Keychain, caminho, ACL ou material de assinatura. */
public enum SecretStatus {
    SECURE_STORAGE_AVAILABLE, LOCKED, DENIED, NOT_CONFIGURED, ERROR
}
