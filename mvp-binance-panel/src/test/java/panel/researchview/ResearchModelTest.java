package panel.researchview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.model.CaptureInfo;
import panel.model.Snapshot;
import panel.model.StageState;
import panel.researchview.ResearchModel.GuardKind;
import panel.researchview.ResearchModel.SegState;
import panel.researchview.ResearchModel.StepState;

class ResearchModelTest {
    private static ResearchModel.Step step(List<ResearchModel.Step> steps, String id) {
        return steps.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theEightStagesFollowTheRealStatesAndNothingIsCompleteForShow() {
        List<ResearchModel.Step> empty = ResearchModel.steps(ResearchFixtures.empty(), false);
        assertEquals(List.of("capture", "dataset", "features", "labels", "hypotheses", "validation", "execution", "live"),
                empty.stream().map(ResearchModel.Step::id).toList());
        assertTrue(empty.stream().noneMatch(s -> s.state() == StepState.COMPLETE), "an empty environment completes nothing");
        assertEquals(StepState.LOCKED, step(empty, "validation").state());
        assertEquals(StepState.LOCKED, step(empty, "live").state());

        List<ResearchModel.Step> ready = ResearchModel.steps(ResearchFixtures.trainReady(34, true), false);
        assertEquals(StepState.CURRENT, step(ready, "capture").state());
        assertTrue(step(ready, "capture").running());
        assertEquals("Running", step(ready, "capture").summary());
        assertEquals(StepState.COMPLETE, step(ready, "dataset").state());
        assertEquals("34 sessions", step(ready, "dataset").summary());
        assertEquals("34 / 34", step(ready, "features").summary());
        assertEquals("34 / 34", step(ready, "labels").summary());
        assertEquals(StepState.PENDING, step(ready, "hypotheses").state());
        assertEquals("0 / 4 completed", step(ready, "hypotheses").summary());
        assertEquals(StepState.LOCKED, step(ready, "validation").state(), "validation is locked whatever the data says");
        assertEquals("Locked", step(ready, "validation").summary());
        assertEquals(StepState.PENDING, step(ready, "execution").state());
    }

    @Test
    void failedPartialAndRunningStagesAreDistinct() {
        Snapshot s = ResearchFixtures.trainReady(10, false);
        s.featureState = StageState.FAILED;
        s.labelState = StageState.PARTIAL;
        s.labelDone = 4;
        List<ResearchModel.Step> steps = ResearchModel.steps(s, true);
        assertEquals(StepState.FAILED, step(steps, "features").state());
        assertEquals(StepState.CURRENT, step(steps, "labels").state());
        assertEquals("4 / 10", step(steps, "labels").summary());
    }

    @Test
    void anUnknownRecorderStringNeverBreaksThePipeline() {
        Snapshot s = ResearchFixtures.trainReady(3, false);
        s.capture = new CaptureInfo("SOMETHING_NEW", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        ResearchModel.Step capture = step(ResearchModel.steps(s, false), "capture");
        assertEquals(StepState.PENDING, capture.state());
        assertFalse(capture.running());
        assertEquals("Something_new", capture.summary());
    }

    @Test
    void theGateIsAConstantOfTheSnapshotAndNoInputOpensIt() throws Exception {
        for (String field : new String[] {"validationStatus", "finalHoldout"}) {
            assertTrue(Modifier.isFinal(Snapshot.class.getField(field).getModifiers()), field + " is final");
        }
        assertEquals("LOCKED", new Snapshot().validationStatus);
        assertEquals("SEALED", new Snapshot().finalHoldout);
        for (Snapshot s : List.of(ResearchFixtures.empty(), ResearchFixtures.trainReady(34, true), partition("VALIDATION"), partition("FINAL_HOLDOUT"), partition(null))) {
            List<ResearchModel.Gate> gates = ResearchModel.guard(s);
            assertEquals(List.of("TRAIN", "VALIDATION", "FINAL_HOLDOUT"), gates.stream().map(ResearchModel.Gate::name).toList());
            assertEquals(GuardKind.LOCKED, gates.get(1).kind(), "VALIDATION stays locked");
            assertEquals("Locked", gates.get(1).state());
            assertEquals(GuardKind.SEALED, gates.get(2).kind(), "FINAL_HOLDOUT stays sealed");
            assertEquals("Sealed", gates.get(2).state());
        }
        assertEquals(GuardKind.OPEN, ResearchModel.guard(ResearchFixtures.trainReady(1, false)).get(0).kind());
        assertEquals(GuardKind.UNKNOWN, ResearchModel.guard(partition("VALIDATION")).get(0).kind(), "TRAIN is open only for a real TRAIN partition");
    }

    private static Snapshot partition(String p) {
        Snapshot s = ResearchFixtures.trainReady(2, false);
        s.partition = p;
        return s;
    }

    @Test
    void sessionsAreRealAndTheActiveOneIsCountedOnce() {
        ResearchModel.Sessions none = ResearchModel.sessions(ResearchFixtures.empty());
        assertTrue(none.segments().isEmpty());
        assertEquals("Session states unavailable", none.summary());

        Snapshot s = ResearchFixtures.trainReady(34, true); // active "s-active" is not in the list: appended once
        ResearchModel.Sessions r = ResearchModel.sessions(s);
        assertEquals(35, r.segments().size());
        assertEquals(SegState.CAPTURING, r.segments().getLast().state());
        assertEquals("34 ready · 1 capturing", r.summary());

        s.capture = new CaptureInfo("RUNNING", null, null, null, null, "s005", "1:00", null, null, null, null, null, null, null, null, null); // active already listed
        r = ResearchModel.sessions(s);
        assertEquals(34, r.segments().size(), "an active session that is already in the total is not duplicated");
        assertEquals(SegState.CAPTURING, r.segments().get(5).state());
        assertEquals("33 ready · 1 capturing", r.summary());

        s = ResearchFixtures.trainReady(6, false);
        s.sessions.set(1, ResearchFixtures.session("s001", StageState.FAILED));
        s.sessions.set(2, ResearchFixtures.session("s002", StageState.MISSING));
        r = ResearchModel.sessions(s);
        assertEquals(SegState.FAILED, r.segments().get(1).state());
        assertEquals(SegState.PENDING, r.segments().get(2).state());
        assertEquals("4 ready · 0 capturing · 1 failed · 1 other", r.summary());
        assertEquals(6, r.segments().size(), "no hard-coded count");
    }

    @Test
    void nextAllowedStepFollowsTheRealRule() {
        assertEquals("dataset", ResearchModel.next(ResearchFixtures.empty(), false).target());
        Snapshot s = ResearchFixtures.trainReady(5, false);
        assertEquals("hypotheses", ResearchModel.next(s, false).target());
        assertEquals("Run hypotheses on TRAIN", ResearchModel.next(s, false).title());
        s.labelState = StageState.PARTIAL;
        assertEquals("labels", ResearchModel.next(s, false).target());
        s.featureState = StageState.PENDING;
        assertEquals("features", ResearchModel.next(s, false).target());
        s.checkpointState = StageState.PENDING;
        assertEquals("dataset", ResearchModel.next(s, false).target());
        for (Snapshot x : List.of(ResearchFixtures.empty(), s, ResearchFixtures.trainReady(2, true))) {
            String t = ResearchModel.next(x, false).target();
            assertFalse(List.of("validation", "execution", "paper", "live").contains(t), "the next step never points at a locked area");
        }
    }

    @Test
    void headlineMetricsAreNaWithoutDataAndNeverInvented() {
        List<ResearchModel.Kpi> k = ResearchModel.kpis(ResearchFixtures.empty());
        assertEquals(List.of("N/A", "N/A", "N/A", "N/A"), k.stream().map(ResearchModel.Kpi::value).toList());
        assertEquals("Recorder N/A", k.get(0).note());
        assertEquals("Dataset N/A", ResearchModel.datasetLabel(ResearchFixtures.empty()));
        assertEquals("0 WARNINGS".equals(ResearchModel.warningText(0)), false);
        assertEquals("No warnings", ResearchModel.warningText(0));
        assertEquals("1 WARNING", ResearchModel.warningText(1));
        assertEquals("3 WARNINGS", ResearchModel.warningText(3));
        List<ResearchModel.Kpi> real = ResearchModel.kpis(ResearchFixtures.trainReady(34, true));
        assertEquals("LIVE", real.get(0).value());
        assertEquals("34", real.get(1).value());
        assertEquals("All labeled", real.get(1).note());
        assertEquals("209,478", real.get(2).value());
        assertEquals("0/4", real.get(3).value());
    }

    @Test
    void fingerprintIgnoresIdentityAndSeesFailedJobs() {
        assertEquals(ResearchModel.fingerprint(ResearchFixtures.trainReady(5, true), false, 0), ResearchModel.fingerprint(ResearchFixtures.trainReady(5, true), false, 0));
        assertTrue(ResearchModel.fingerprint(ResearchFixtures.trainReady(5, true), false, 0) != ResearchModel.fingerprint(ResearchFixtures.trainReady(5, true), false, 1));
        assertTrue(ResearchModel.fingerprint(ResearchFixtures.trainReady(5, true), false, 0) != ResearchModel.fingerprint(ResearchFixtures.trainReady(6, true), false, 0));
    }
}
