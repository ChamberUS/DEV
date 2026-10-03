package panel.adapter;

import panel.model.CaptureSnapshot;

/** Read-only source boundary, replaceable with a remote monitor. */
@FunctionalInterface
public interface CaptureProcessProbe {
    CaptureSnapshot read() throws Exception;
}
