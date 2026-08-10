package mmmm.block;

import mmmm.MmmmContent;
import mmmm.Stations;
import mmmm.core.relay.RelaySession;
import mmmm.server.RadioServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.net.URI;

/**
 * What a placed radio remembers: which station, whether it is on, and how loud.
 *
 * <h2>Why there is no custom packet for any of this</h2>
 * Vanilla already synchronises block entities — {@link #getUpdatePacket()} on change,
 * {@link #getUpdateTag()} on chunk load — and that machinery handles chunk tracking, players joining
 * mid-session and reconnects without any help. Only the media stream itself needs packets of its
 * own (master plan §5.2), because only it is high-rate and player-scoped.
 *
 * <p>{@code sessionId} rides along on that same sync, which is what lets a client match a block to
 * the audio stream arriving for it without a further round trip.
 */
public class RadioBlockEntity extends BlockEntity {

    /** Not a valid session id; means "this block is not attached to a relay". */
    public static final int NO_SESSION = -1;

    private static final String KEY_STATION = "Station";
    private static final String KEY_PLAYING = "Playing";
    private static final String KEY_VOLUME = "Volume";
    private static final String KEY_SESSION = "SessionId";

    private String station = Stations.defaultStation().url();
    private boolean playing;
    private float volume = 1.0F;
    private int sessionId = NO_SESSION;

    /**
     * The relay this block is holding open, server side only.
     *
     * <p>Lives here rather than in a map keyed by position because the lifetimes are identical: the
     * claim is acquired when the block starts playing and must be released when it stops, is broken,
     * or its chunk unloads — all of which are events this object already receives. A side map would
     * have to be kept in step with all three, and the one that gets forgotten is chunk unload, which
     * leaks a socket per unloaded radio. Only {@code mmmm.server.RadioServer} touches it.
     */
    private transient RelaySession serverSession;
    private transient URI heldStation;

    public RadioBlockEntity(BlockPos pos, BlockState state) {
        super(MmmmContent.radioBlockEntity(), pos, state);
    }

    // ------------------------------------------------------------------ state

    public String getStation() {
        return station;
    }

    public boolean isPlaying() {
        return playing;
    }

    public float getVolume() {
        return volume;
    }

    public int getSessionId() {
        return sessionId;
    }

    public void setStation(String station) {
        if (!this.station.equals(station)) {
            this.station = station;
            sync();
        }
    }

    public void setPlaying(boolean playing) {
        if (this.playing != playing) {
            this.playing = playing;
            sync();
        }
    }

    public void setVolume(float volume) {
        float clamped = Math.max(0.0F, Math.min(1.0F, volume));
        if (this.volume != clamped) {
            this.volume = clamped;
            sync();
        }
    }

    public void setSessionId(int sessionId) {
        if (this.sessionId != sessionId) {
            this.sessionId = sessionId;
            sync();
        }
    }

    /** Server-side relay bookkeeping; see the field comment. */
    public RelaySession getServerSession() {
        return serverSession;
    }

    public void setServerSession(RelaySession session, URI station) {
        this.serverSession = session;
        this.heldStation = station;
    }

    public URI getHeldStation() {
        return heldStation;
    }

    /**
     * Marks the block changed and pushes it to everyone tracking the chunk.
     *
     * <p>{@code setChanged} alone only marks the chunk for saving; without the block update, a
     * client would not learn the radio had been switched on until it reloaded the chunk.
     */
    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    // ------------------------------------------------------------------ persistence and sync

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(KEY_STATION, Tag.TAG_STRING)) {
            station = tag.getString(KEY_STATION);
        }
        playing = tag.getBoolean(KEY_PLAYING);
        if (tag.contains(KEY_VOLUME, Tag.TAG_FLOAT)) {
            volume = tag.getFloat(KEY_VOLUME);
        }
        // Absent from disk on purpose: a session id is valid only for the server run that issued it.
        sessionId = tag.contains(KEY_SESSION, Tag.TAG_INT) ? tag.getInt(KEY_SESSION) : NO_SESSION;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString(KEY_STATION, station);
        tag.putBoolean(KEY_PLAYING, playing);
        tag.putFloat(KEY_VOLUME, volume);
    }

    /** Sent on chunk load. Carries the session id, which {@link #saveAdditional} deliberately omits. */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        tag.putInt(KEY_SESSION, sessionId);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * Drops the upstream claim however the block goes away.
     *
     * <p>Vanilla routes breaking, chunk unload and world unload all through here, so one override
     * covers all three. Hooking only the break path is the classic version of this bug: the radio in
     * a chunk nobody has visited for an hour is still holding a socket open.
     *
     * <p>The client has no equivalent hook and needs none — {@code ClientMedia} notices that a block
     * has stopped ticking and closes its decoder, which covers the same three cases plus changing
     * dimension.
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide) {
            RadioServer.blockRemoved(this);
        }
    }
}
