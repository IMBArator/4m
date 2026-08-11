package mmmm.client;

import mmmm.Stations;
import mmmm.block.RadioBlockEntity;
import mmmm.core.relay.SessionState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The radio's control panel: pick a station, start and stop it, set how loud it is.
 *
 * <h2>Why a plain Screen and not a container menu</h2>
 * The radio holds no items, and everything shown here is already on the block entity and already
 * synced to every client by vanilla's block-update path. A {@code MenuType} would add a registry
 * entry, a {@code MenuScreens} binding and a {@code NetworkHooks.openScreen} call — the last of which
 * is loader API this package may not touch (ADR-0002) — in exchange for a slot container nothing
 * would put anything in.
 *
 * <h2>The screen never owns state</h2>
 * Every control sends a {@link ClientMessages.ConfigureRadio} and then waits. Nothing is applied
 * locally, so what you see is always what the server last agreed to. That is what makes a refused
 * change — a non-operator trying to set a custom URL, say — visibly snap back rather than leaving
 * the screen quietly disagreeing with the world.
 */
public final class RadioScreen extends Screen {

    private static final int PANEL_WIDTH = 260;
    private static final int ROW_HEIGHT = 20;
    private static final int ENTRY_HEIGHT = 14;

    private final RadioBlockEntity radio;

    private StationList stationList;
    private Button playStop;
    private VolumeSlider volume;
    private EditBox urlBox;

    /** The station the list was last built for, so another player's change rebuilds it. */
    private String shownStation = "";

    /** Redraw the readout four times a second. Faster than this and the digits cannot be read. */
    private static final int HEALTH_TEXT_INTERVAL_TICKS = 5;

    private int ticksSinceHealthText;
    private Component healthLine;

    public RadioScreen(RadioBlockEntity radio) {
        super(Component.translatable("gui.mmmm.radio.title"));
        this.radio = radio;
    }

    /**
     * Never pauses the game.
     *
     * <p>{@link Screen#isPauseScreen()} defaults to true, and in singleplayer a paused game stops
     * the client tick — which is where the drift loop runs and where decoded audio is steered. The
     * radio would stall for as long as its own control panel was open.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int listTop = 40;
        int listBottom = height - 96;

        stationList = new StationList(minecraft, PANEL_WIDTH, height, listTop, listBottom, ENTRY_HEIGHT);
        stationList.setLeftPos(left);
        addRenderableWidget(stationList);
        rebuildStations();

        int controlsY = height - 88;
        playStop = addRenderableWidget(Button.builder(playLabel(), b -> togglePlay())
                .bounds(left, controlsY, 72, ROW_HEIGHT)
                .build());
        volume = addRenderableWidget(new VolumeSlider(
                left + 78, controlsY, PANEL_WIDTH - 78, ROW_HEIGHT, radio.getVolume()));

        // Custom URLs are operator-only (ADR-0011). Hiding the control for everyone else is a
        // courtesy, not a control: ServerNetwork re-checks the permission on every message.
        if (mayUseCustomUrl()) {
            int urlY = controlsY + ROW_HEIGHT + 4;
            urlBox = new EditBox(font, left, urlY, PANEL_WIDTH - 46, ROW_HEIGHT,
                    Component.translatable("gui.mmmm.radio.url"));
            urlBox.setMaxLength(400);
            urlBox.setHint(Component.translatable("gui.mmmm.radio.url_hint"));
            if (isCustomStation(radio.getStation())) {
                urlBox.setValue(radio.getStation());
            }
            addRenderableWidget(urlBox);
            addRenderableWidget(Button.builder(Component.translatable("gui.mmmm.radio.set"), b -> submitUrl())
                    .bounds(left + PANEL_WIDTH - 42, urlY, 42, ROW_HEIGHT)
                    .build());
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (radio.isRemoved()) {
            // Somebody broke the radio while its panel was open.
            onClose();
            return;
        }
        if (urlBox != null) {
            urlBox.tick();
        }
        playStop.setMessage(playLabel());
        volume.onScreenTick();
        if (!shownStation.equals(radio.getStation())) {
            rebuildStations();
        }
        updateSyncHealth();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal(Stations.displayName(radio.getStation())),
                width / 2, 26, 0xA0A0A0);

        graphics.drawCenteredString(font, statusLine(), width / 2, height - 40, 0xA0A0A0);
        Component playing = nowPlaying();
        if (playing != null) {
            graphics.drawCenteredString(font, playing, width / 2, height - 28, 0xFFE082);
        }

        Component health = syncHealth();
        if (health != null) {
            // Left-aligned, unlike everything else on this panel, and deliberately. Centred text
            // re-centres whenever its length changes, so every digit slides sideways each time the
            // drift gains or loses a character — which makes a line of changing numbers unreadable
            // however slowly it updates. Anchoring the left edge keeps each field in one place.
            graphics.drawString(font, health, (width - PANEL_WIDTH) / 2, height - 16, 0xB0B0B0, false);
        }
    }

    // ------------------------------------------------------------------ actions

    private void togglePlay() {
        send(radio.getStation(), !radio.isPlaying());
    }

    private void selectStation(String url) {
        // Choosing a station implies wanting to hear it; a picker that silently did nothing until
        // you also pressed Play would read as broken.
        send(url, true);
    }

    private void submitUrl() {
        String url = urlBox.getValue().trim();
        if (!url.isEmpty()) {
            send(url, true);
        }
    }

    private void send(String station, boolean playing) {
        ClientNetwork.sendConfigure(radio.getBlockPos(), station, playing, (float) volume.value());
    }

    // ------------------------------------------------------------------ display helpers

    private Component playLabel() {
        return Component.translatable(radio.isPlaying() ? "gui.mmmm.radio.stop" : "gui.mmmm.radio.play");
    }

    private boolean mayUseCustomUrl() {
        return minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    private static boolean isCustomStation(String url) {
        for (Stations.Station station : Stations.DEFAULTS) {
            if (station.url().equals(url)) {
                return false;
            }
        }
        return !url.isEmpty();
    }

    private void rebuildStations() {
        shownStation = radio.getStation();
        stationList.rebuild();
    }

    /**
     * The status line.
     *
     * <p>Reports {@code BUFFERING} while the relay says {@code PLAYING} but no audio has reached this
     * client yet. That gap is real and several seconds long — playback waits for the clock to
     * converge and for the ring to hold a full presentation delay — so claiming "playing" would be a
     * lie during exactly the window a player is most likely to be staring at the screen wondering.
     */
    private Component statusLine() {
        SessionState state = radio.getSessionState();
        String key;
        if (state == null) {
            key = radio.isPlaying() ? "starting" : "off";
        } else {
            key = switch (state) {
                case CONNECTING -> "connecting";
                case BUFFERING -> "buffering";
                case PLAYING -> audioReachedThisClient() ? "playing" : "buffering";
                case RECONNECTING -> "reconnecting";
                case FAILED -> "failed";
                case CLOSED -> "off";
            };
        }
        return Component.translatable("gui.mmmm.radio.status",
                Component.translatable("gui.mmmm.radio.state." + key));
    }

    private boolean audioReachedThisClient() {
        ClientMediaSession session = ClientMedia.sessionForBlock(radio.getBlockPos());
        return session != null && session.hasAudio();
    }

    private Component nowPlaying() {
        if (radio.getSessionId() == RadioBlockEntity.NO_SESSION) {
            return null;
        }
        String title = ClientMedia.titleFor(radio.getSessionId());
        return title == null || title.isBlank() ? null : Component.literal(title);
    }

    /**
     * The sync-health line: what this client's copy of the shared clock is doing.
     *
     * <p>Everything the design rests on is invisible without it. Two clients a quarter-second apart
     * sound obviously wrong but give no clue <em>why</em>, and the three candidate causes leave
     * different traces here: a clock that never converged, a buffer running dry, or a rate trim
     * pinned at its limit and losing.
     *
     * <pre>
     *   drift +12ms · buf 2.9s · trim +38ppm · rtt 4ms
     * </pre>
     *
     * <ul>
     *   <li><b>drift</b> — how far playback is from where the shared clock says it should be,
     *       positive when behind. <em>This is the number the whole project is about.</em> Under
     *       10 ms it sits in the controller's deadband and is not being corrected at all, which is
     *       intended, not a stall.</li>
     *   <li><b>buf</b> — decoded audio in hand. Should hover near the presentation delay; falling
     *       towards zero is an underrun coming.</li>
     *   <li><b>trim</b> — the playback-rate correction, in ppm. Worth more attention than it looks:
     *       a <em>steady non-zero</em> trim is the controller cancelling this machine's sound-card
     *       error, which is exactly what the integral term exists for (§5.3), so a value parked at
     *       40 ppm is the system working rather than failing. Pinned at ±1000 is the ceiling, and
     *       means it cannot keep up.</li>
     *   <li><b>rtt</b> — the best round trip seen. The clock estimate is no better than about half
     *       of this, so a large value bounds how good sync can possibly be.</li>
     * </ul>
     *
     * <p>Two counters appear only when they are not zero, because a zero there is the normal case
     * and a permanent {@code resync 0} trains the eye to skip the whole line: <b>resync</b> (hard
     * jumps, each one audible) and <b>dropped</b> (frames discarded before decode).
     *
     * <p>Not gated behind F3. It is the instrument for the multi-client sync work and belongs
     * wherever the radio's state is being looked at; it is dim, one line, and only rendered when a
     * session actually exists.
     */
    private Component syncHealth() {
        return healthLine;
    }

    /**
     * Rebuilds the readout. Called from {@link #tick()}, never from {@code render}.
     *
     * <p>Both halves of that matter. Sampling belongs on the tick because the drift loop runs at
     * 20 Hz and one sample per step is what the window assumes; sampling in {@code render} would
     * weight whatever the frame rate happens to be. And the *text* is rebuilt at 4 Hz because
     * numbers redrawn every frame cannot be read at all — the first version of this line was
     * reported, accurately, as "hard to read".
     */
    private void updateSyncHealth() {
        if (!ClientDebug.syncReadout()) {
            healthLine = null;
            return;
        }
        if (++ticksSinceHealthText < HEALTH_TEXT_INTERVAL_TICKS && healthLine != null) {
            // Rebuilt at 4 Hz, not per frame and not per tick. Numbers redrawn any faster cannot be
            // read at all — the first version of this line was reported, accurately, as "hard to
            // read". The meter underneath still samples every drift step; only the text is throttled.
            return;
        }
        ticksSinceHealthText = 0;
        String line = SyncHealthLine.of(ClientMedia.sessionForBlock(radio.getBlockPos()), ClientMedia.clock());
        healthLine = line == null ? null : Component.literal(line);
    }




    // ------------------------------------------------------------------ widgets

    /**
     * Volume, 0..1.
     *
     * <h2>Why dragging sends, rather than only releasing</h2>
     * The sound follows the <em>block entity's</em> volume, which only changes once the server has
     * accepted it. So a slider that sent only on release moved visually while nothing audible
     * happened — the change landed all at once when you let go. Sending during the drag is what
     * makes it live, and it keeps the block entity the single authority rather than introducing a
     * local preview value that only the dragging player can hear.
     *
     * <p>Throttled to the tick, not per mouse event: a drag fires dozens of events a second, and
     * every accepted change costs a block update to each client tracking that chunk. At 10 Hz the
     * ramp is smooth to the ear — volume is not pitch — and the traffic is a rounding error beside
     * the audio itself.
     */
    private final class VolumeSlider extends AbstractSliderButton {

        /** Client ticks between sends while dragging. 20 Hz / 2 = 10 Hz. */
        private static final int SEND_INTERVAL_TICKS = 2;

        private boolean adjustingWithMouse;
        private double lastSent = -1.0;
        private int ticksSinceSend;

        VolumeSlider(int x, int y, int width, int height, double initial) {
            super(x, y, width, height, Component.empty(), Mth.clamp(initial, 0.0, 1.0));
            updateMessage();
        }

        double value() {
            return value;
        }

        /** Pushes the in-progress value while dragging; otherwise follows the block entity. */
        void onScreenTick() {
            if (adjustingWithMouse) {
                ticksSinceSend++;
                if (ticksSinceSend >= SEND_INTERVAL_TICKS && Math.abs(value - lastSent) > 0.005) {
                    sendVolume();
                }
                return;
            }
            // Not being dragged, so the server — and therefore another player's change — wins.
            if (Math.abs(value - radio.getVolume()) > 0.001) {
                value = Mth.clamp(radio.getVolume(), 0.0F, 1.0F);
                updateMessage();
            }
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("gui.mmmm.radio.volume", (int) Math.round(value * 100)));
        }

        @Override
        protected void applyValue() {
            updateMessage();
            if (!adjustingWithMouse) {
                // Keyboard adjustment — there is no release coming to wait for.
                sendVolume();
            }
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            adjustingWithMouse = true;
            super.onClick(mouseX, mouseY);
        }

        @Override
        protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
            adjustingWithMouse = true;
            super.onDrag(mouseX, mouseY, dragX, dragY);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            adjustingWithMouse = false;
            sendVolume();
        }

        private void sendVolume() {
            ClientNetwork.sendConfigure(radio.getBlockPos(), radio.getStation(),
                    radio.isPlaying(), (float) value);
        }
    }

    /** The station picker: the shipped list, plus this radio's custom URL when it has one. */
    private final class StationList extends ObjectSelectionList<StationList.StationEntry> {

        StationList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        void rebuild() {
            clearEntries();
            String current = radio.getStation();
            boolean matched = false;

            for (Stations.Station station : Stations.DEFAULTS) {
                StationEntry entry = new StationEntry(station.name(), station.url());
                addEntry(entry);
                if (station.url().equals(current)) {
                    setSelected(entry);
                    matched = true;
                }
            }

            // A radio pointed at something an operator added is shown as its own row rather than
            // silently deselecting everything.
            if (!matched && !current.isEmpty()) {
                StationEntry entry = new StationEntry(
                        Component.translatable("gui.mmmm.radio.custom_station").getString() + " " + current,
                        current);
                addEntry(entry);
                setSelected(entry);
            }
        }

        @Override
        public int getRowWidth() {
            return this.width - 12;
        }

        final class StationEntry extends ObjectSelectionList.Entry<StationEntry> {

            private final String label;
            private final String url;

            StationEntry(String label, String url) {
                this.label = label;
                this.url = url;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int entryWidth,
                               int entryHeight, int mouseX, int mouseY, boolean hovering, float partialTick) {
                graphics.drawString(font, label, left + 3, top + 2, 0xFFFFFF, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    setSelected(this);
                    selectStation(url);
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() {
                return Component.literal(label);
            }
        }
    }
}
