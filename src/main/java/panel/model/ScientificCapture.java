package panel.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Estado da captura CIENTÍFICA real (o coletor de microestrutura da campanha), resumido para a UI.
 * <p>
 * Semântica (fechada; UNKNOWN nunca vira STOPPED):
 * <ul>
 * <li>{@code RUNNING}: supervisor e coletor verificados e coerentes com a campanha registrada, e o arquivo {@code .part} ABERTO pelo
 * coletor teve escrita recente (ou a sessão está rodando normalmente a rodar/rotacionar dentro da tolerância de rotação).</li>
 * <li>{@code DEGRADED}: o processo está vivo, mas há risco atual: escrita silenciosa além do limite sem morte confirmada, sessão sem
 * arquivo de escrita além da tolerância de rotação, ou a ÚLTIMA sessão fechada foi rejeitada na admissão (incidente atual).</li>
 * <li>{@code STOPPED}: a fonte autoritativa (resolvedor de runtime) provou que não há coletor nem supervisor registrado vivo.</li>
 * <li>{@code UNKNOWN}: evidência insuficiente, erro ao resolver, ambiguidade ou leitura que estourou o limite de tempo.</li>
 * </ul>
 */
public record ScientificCapture(Status status, String reason, String campaign, String configHash, String session, Duration lastWriteAge,
        Long supervisorPid, Long collectorPid, Admission admission, String admissionReason, Instant checkedAt) {
    public enum Status { RUNNING, DEGRADED, STOPPED, UNKNOWN }

    /** Admissão da última sessão FECHADA (scientific_admission.json): NONE = ainda não há sessão fechada para julgar. */
    public enum Admission { ADMITTED, REJECTED, NONE, UNKNOWN }

    public static ScientificCapture unknown(String reason, Instant now) {
        return new ScientificCapture(Status.UNKNOWN, reason, null, null, null, null, null, null, Admission.UNKNOWN, null, now);
    }

    public static ScientificCapture unknown(String reason) {
        return unknown(reason, Instant.now());
    }

    /** Linha curta para dica/diagnóstico: sem caminhos, sem nomes de máquina. */
    public String summary() {
        StringBuilder b = new StringBuilder(status.name());
        if (reason != null && !reason.isBlank()) {
            b.append(" · ").append(reason);
        }
        if (campaign != null) {
            b.append(" · campaign ").append(campaign);
        }
        if (session != null) {
            b.append(" · session ").append(session);
        }
        if (lastWriteAge != null) {
            b.append(" · last write ").append(Math.max(0, lastWriteAge.toSeconds())).append(" s ago");
        }
        return b.toString();
    }
}
