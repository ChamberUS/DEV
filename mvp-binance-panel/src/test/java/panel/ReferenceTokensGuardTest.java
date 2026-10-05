package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.motion.MotionPreference;
import panel.motion.MotionSpec;

/**
 * Guarda dos tokens oficiais do handoff V2 FINAL (3.0.0-final). Os JSONs empacotados são cópias byte a byte
 * da referência; MotionSpec resolve exatamente os tokens dela, sem tokens derivados.
 * REFERENCE TOKENS: 79 (74 das fases 1–3 + 5 brandField* do P3.20). DERIVED/INTERNAL: 0. TOTAL RESOLVED: 79.
 */
class ReferenceTokensGuardTest {
    private static final String MOTION_SHA256 = "1b0ed3408ab43ff51fc55560c9b432450aa3d9da3204d7f834b5093369048ab2";
    private static final String DESIGN_SHA256 = "2a5ddbd3a6538feff7b7ad7eacf808a4382756487862a7c0cf16ed28054dc331";

    /** As 74 entradas das fases 1–3. */
    private static final List<String> PHASES_1_TO_3 = List.of("hover", "press", "focus", "tooltip", "pageEnter", "pageExit",
            "authTransition", "workspaceChange", "railIndicator", "overlayOpen", "overlayClose", "menuOpen", "menuClose",
            "modalOpen", "modalClose", "toastEnter", "toastExit", "loading", "shimmer", "breathing", "errorFeedback",
            "successFeedback", "progressFill", "fieldValidation", "dockStatusChange", "cardEnter", "userMenuOpen",
            "userMenuClose", "notificationPanelOpen", "notificationPanelClose", "notificationMarkRead", "badgeCountChange",
            "accordionExpand", "accordionCollapse", "settingsSectionChange", "settingsSaveBarShow", "settingsSaveBarHide",
            "toggleSwitch", "profileEditEnter", "profileSaved", "searchResultsUpdate", "paletteOpen", "paletteClose",
            "dialogOpen", "dialogClose", "rowRemove", "toastStack", "regionLoading", "systemChipChange", "statePulse",
            "restoredChipHold", "globalBarShow", "globalBarHide", "warningBannerEnter", "warningBannerExit",
            "inlineFieldError", "regionStateSwap", "staleDim", "loadingSlowNote", "sessionDialogEnter", "sessionDialogExit",
            "sessionFlowStep", "onboardingStepEnter", "onboardingStepExit", "onboardingProgress", "onboardingOption",
            "startupStep", "startupExit", "dockLinkHover", "welcomeEnter", "firstMountPageEnter", "returnToExistingView",
            "navigationInterrupt", "dataUpdateResizeRefresh");
    /** P3.20 brand field (auth e welcome). */
    private static final List<String> BRAND_FIELD = List.of("brandFieldCycle", "brandFieldDrift", "brandFieldTagline",
            "brandFieldGlow", "brandFieldStatic");

    private static String sha256(String resource) throws Exception {
        try (InputStream in = ReferenceTokensGuardTest.class.getResourceAsStream(resource)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }
    }

    @Test
    void bundledJsonIsTheUnmodifiedReference() throws Exception {
        assertEquals(MOTION_SHA256, sha256(MotionSpec.RESOURCE), "BYX_MOTION_TOKENS.json differs from the V2 FINAL reference");
        assertEquals(DESIGN_SHA256, sha256(panel.design.DesignTokens.RESOURCE), "BYX_DESIGN_TOKENS.json differs from the V2 FINAL reference");
    }

    @Test
    void motionSpecResolvesExactlyTheReferenceTokens() {
        assertEquals(74, PHASES_1_TO_3.size());
        assertEquals(5, BRAND_FIELD.size());
        List<String> reference = new java.util.ArrayList<>(PHASES_1_TO_3);
        reference.addAll(BRAND_FIELD);
        assertEquals(reference, List.copyOf(MotionSpec.get().tokens().keySet()), "reference tokens lost, renamed or added");
        for (MotionPreference m : MotionPreference.values()) {
            reference.forEach(t -> MotionSpec.get().token(t).resolve(m));
        }
    }
}
