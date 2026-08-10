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
        volume.followBlock();
        if (!shownStation.equals(radio.getStation())) {
            rebuildStations();
        }
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

    // ------------------------------------------------------------------ widgets

    /** Volume, 0..1, sent once per adjustment rather than once per pixel of drag. */
    private final class VolumeSlider extends AbstractSliderButton {

        private boolean adjustingWithMouse;

        VolumeSlider(int x, int y, int width, int height, double initial) {
            super(x, y, width, height, Component.empty(), Mth.clamp(initial, 0.0, 1.0));
            updateMessage();
        }

        double value() {
            return value;
        }

        /** Follows the block entity unless the player is mid-drag, so another client's change lands. */
        void followBlock() {
            if (!adjustingWithMouse && Math.abs(value - radio.getVolume()) > 0.001) {
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
