package panel.model;

/** Etapa do pipeline; {@code target} é o id da tela aberta ao clicar. */
public record PipelineStage(String title, StageState state, String summary, String target) {
}
