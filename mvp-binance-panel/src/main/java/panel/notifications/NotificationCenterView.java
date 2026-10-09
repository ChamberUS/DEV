package panel.notifications;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import javafx.beans.InvalidationListener;
import javafx.collections.ListChangeListener;
import javafx.scene.AccessibleRole;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import panel.i18n.DisplayFormats;
import panel.i18n.LocaleView;
import panel.i18n.Strings;

/** Native reusable list for the existing popover and existing Account route. No timer or IO. */
public final class NotificationCenterView extends VBox implements AutoCloseable {
    private final NotificationCenter center;
    private final java.util.function.Consumer<NotificationEvent.Destination> navigate;
    private final Clock clock;
    private final Label title = new Label(), availability = new Label(), retention = new Label(), empty = new Label();
    private final ListView<NotificationEvent> list;
    private final Button all = new Button(), read = new Button(), open = new Button();
    private final InvalidationListener labels = o -> refresh();
    private final ListChangeListener<NotificationEvent> changes = c -> refresh();
    private boolean closed, subscribed;
    private java.util.UUID detachedSelection;
    private final javafx.beans.value.ChangeListener<javafx.scene.Scene> sceneListener = (o, before, after) -> {
        if (after == null) unsubscribe(); else subscribe();
    };
    public NotificationCenterView(NotificationCenter center, java.util.function.Consumer<NotificationEvent.Destination> navigate, Clock clock) {
        super(10); this.center = center; this.navigate = navigate; this.clock = clock;
        LocaleView.literal(this); setId("notification-center"); setAccessibleRole(AccessibleRole.PARENT);
        title.getStyleClass().add("byx-section-title-sm");
        all.setId("notification-mark-all"); read.setId("notification-read"); open.setId("notification-open");
        for (Button b : new Button[]{all, read, open}) b.getStyleClass().addAll("byx-btn", "ghost", "small");
        all.setOnAction(e -> center.readAll());
        list = new ListView<>(); list.setId("notification-list"); list.getStyleClass().add("byx-notification-list");
        list.setPrefHeight(250); list.setMinHeight(140); list.setMaxHeight(280); list.setPlaceholder(empty);
        list.setCellFactory(v -> new ListCell<>() {
            { prefWidthProperty().bind(list.widthProperty().subtract(24)); setMinWidth(0); }
            @Override protected void updateItem(NotificationEvent event, boolean blank) {
                super.updateItem(event, blank);
                if (blank || event == null) { setText(null); setGraphic(null); setAccessibleText(null); return; }
                String severity = Strings.get("notification.severity." + event.type().severity.name().toLowerCase(java.util.Locale.ROOT));
                String status = Strings.get(event.read() ? "notification.read" : "notification.unread");
                Label icon = new Label(switch (event.type().severity) { case INFO -> "ⓘ"; case WARNING -> "⚠"; case ERROR -> "!"; });
                icon.setAccessibleText(severity); icon.setMinWidth(Region.USE_PREF_SIZE); icon.getStyleClass().add("byx-notification-severity");
                Label heading = new Label(severity + " · " + status); heading.getStyleClass().addAll("byx-body", "byx-secondary");
                Label description = new Label(Strings.get(event.type().key)); description.setWrapText(true); description.setMaxWidth(340);
                description.getStyleClass().addAll("byx-body", "byx-notification-description");
                Label time = new Label(relative(event.at()) + (event.occurrences() > 1 ? " · " + Strings.fmt("notification.repeated", "count", DisplayFormats.number(event.occurrences(), 0)) : ""));
                time.getStyleClass().addAll("byx-body", "byx-secondary");
                String absolute = DisplayFormats.dateTime(event.at(), ZoneId.systemDefault()) + " " + ZoneId.systemDefault().getId();
                time.setTooltip(new Tooltip(absolute));
                VBox body = new VBox(4, heading, description, time); body.setMinWidth(0); HBox.setHgrow(body, Priority.ALWAYS);
                setGraphic(new HBox(8, icon, body)); setText(null);
                setAccessibleText(severity + ". " + description.getText() + ". " + status + ". " + absolute + ". "
                        + Strings.fmt("notification.repeated", "count", DisplayFormats.number(event.occurrences(), 0)));
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener(o -> controls());
        list.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) { activate(); e.consume(); } });
        read.setOnAction(e -> { var item = list.getSelectionModel().getSelectedItem(); if (item != null) center.read(item.id(), !item.read()); });
        open.setOnAction(e -> activate());
        availability.setWrapText(true); retention.setWrapText(true); empty.setWrapText(true); empty.setMaxWidth(310);
        for (Label l : new Label[]{availability, retention, empty}) l.getStyleClass().addAll("byx-body", "byx-secondary");
        getChildren().addAll(title, all, availability, list, new HBox(12, read, open), retention);
        sceneProperty().addListener(sceneListener); refresh();
        if (getScene() != null) subscribe();
    }
    private String relative(java.time.Instant at) {
        long seconds = Math.max(0, Duration.between(at, clock.instant()).getSeconds());
        return seconds < 60 ? Strings.get("notification.justNow") : seconds < 3600
                ? Strings.fmt("notification.minutesAgo", "count", DisplayFormats.number(seconds / 60, 0))
                : Strings.fmt("notification.hoursAgo", "count", DisplayFormats.number(seconds / 3600, 0));
    }
    private void refresh() {
        if (closed) return;
        var selected = list.getSelectionModel().getSelectedItem(); var selectedId = selected == null ? null : selected.id();
        title.setText(Strings.fmt("notification.titleCount", "count", DisplayFormats.number(center.unreadProperty().get(), 0)));
        setAccessibleText(title.getText()); list.setAccessibleText(Strings.get("notification.title"));
        empty.setText(Strings.get(center.active() ? "notification.empty" : "notification.unavailable"));
        all.setText(Strings.get("notification.markAll")); all.setDisable(!center.active() || center.unreadProperty().get() == 0);
        open.setText(Strings.get("notification.open"));
        availability.setText(Strings.get("notification.source." + center.sourceProperty().get().name().toLowerCase(java.util.Locale.ROOT)));
        retention.setText(Strings.get("notification.localHistory"));
        list.refresh();
        if (selectedId != null) center.events().stream().filter(e -> e.id().equals(selectedId)).findFirst().ifPresent(e -> list.getSelectionModel().select(e));
        controls();
    }
    private void controls() {
        var item = list.getSelectionModel().getSelectedItem();
        read.setText(Strings.get(item != null && item.read() ? "notification.markUnread" : "notification.markRead"));
        read.setDisable(item == null || !center.active()); open.setDisable(item == null || !center.active());
    }
    public void activate() {
        var item = list.getSelectionModel().getSelectedItem(); if (item == null || !center.active() || center.events().stream().noneMatch(e -> e.id().equals(item.id()))) return;
        center.read(item.id(), true); navigate.accept(item.type().destination);
    }
    public void focusList() { if (!center.events().isEmpty()) list.getSelectionModel().selectFirst(); list.requestFocus(); }

    private void subscribe() {
        if (closed || subscribed) return; subscribed = true;
        list.setItems(center.events());
        if (detachedSelection != null) center.events().stream().filter(e -> e.id().equals(detachedSelection)).findFirst().ifPresent(e -> list.getSelectionModel().select(e));
        center.events().addListener(changes); center.unreadProperty().addListener(labels); center.sourceProperty().addListener(labels);
        Strings.languageProperty().addListener(labels); refresh();
    }
    private void unsubscribe() {
        if (!subscribed) return; subscribed = false;
        center.events().removeListener(changes); center.unreadProperty().removeListener(labels); center.sourceProperty().removeListener(labels);
        Strings.languageProperty().removeListener(labels);
        var selected = list.getSelectionModel().getSelectedItem(); detachedSelection = selected == null ? null : selected.id();
        list.setItems(javafx.collections.FXCollections.observableArrayList());
    }
    @Override public void close() {
        if (closed) return; closed = true;
        sceneProperty().removeListener(sceneListener); unsubscribe();

        list.setOnKeyPressed(null); all.setOnAction(null); read.setOnAction(null); open.setOnAction(null);
    }
}
