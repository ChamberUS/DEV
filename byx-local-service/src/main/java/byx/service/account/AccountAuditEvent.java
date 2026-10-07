package byx.service.account;

/** Eventos de auditoria SEGUROS das capacidades privadas (design). Só o tipo, o id interno da conta (hash) e um código de resultado fixo: nunca chave, assinatura, query assinada, saldos nem identificador externo. */
public enum AccountAuditEvent {
    ACCOUNT_READ_REQUESTED, ACCOUNT_READ_OK, ACCOUNT_READ_FAILED,
    CREDENTIAL_CONFIGURED, CREDENTIAL_REJECTED_PERMISSIONS, CREDENTIAL_REMOVED,
    NOTIFICATIONS_SUBSCRIBED, NOTIFICATIONS_UNSUBSCRIBED
}
