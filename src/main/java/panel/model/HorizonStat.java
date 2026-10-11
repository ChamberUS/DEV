package panel.model;

/** Estatísticas por horizonte; valores nulos = N/A. */
public record HorizonStat(long horizonMs, Long valid, Long invalid, Double coverage,
                          Double medianErrorMs, Double p95ErrorMs, Double p99ErrorMs, Double maxErrorMs,
                          boolean partial) {
}
