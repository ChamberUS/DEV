package panel.model;

import java.time.Duration;
import java.time.Instant;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import panel.adapter.CommandSpec;

public class JobRecord {
    public final String id;
    public final CommandSpec spec;
    public final String title;
    public final String command;
    public final boolean external;
    public final ObjectProperty<JobState> state = new SimpleObjectProperty<>(JobState.QUEUED);
    public final StringProperty progress = new SimpleStringProperty("N/A");
    public final ObjectProperty<Instant> startedAt = new SimpleObjectProperty<>();
    public final ObjectProperty<Instant> finishedAt = new SimpleObjectProperty<>();
    public final IntegerProperty exitCode = new SimpleIntegerProperty(Integer.MIN_VALUE);

    public JobRecord(String id, CommandSpec spec, String title, String command, boolean external) {
        this.id = id;
        this.spec = spec;
        this.title = title;
        this.command = command;
        this.external = external;
    }

    public Duration elapsed() {
        Instant s = startedAt.get();
        if (s == null) {
            return null;
        }
        Instant e = finishedAt.get();
        return Duration.between(s, e == null ? Instant.now() : e);
    }

    public boolean active() {
        return state.get() == JobState.QUEUED || state.get() == JobState.RUNNING;
    }
}
