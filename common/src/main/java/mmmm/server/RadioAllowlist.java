package mmmm.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Hosts an operator has authorised the server to stream from, beyond the shipped station list.
 *
 * <h2>Why this is persisted, and why it is not a config file</h2>
 * ADR-0011 makes free-form URLs "opt-in per server and gated on permission level". An operator
 * setting a custom station on a radio <em>is</em> that opt-in — but it has to survive a restart, or
 * the block still points at the URL while the allowlist no longer permits it, and the radio dies on
 * the next server start for reasons nobody can see. So the authorisation is stored next to the world
 * it applies to.
 *
 * <p>A {@link SavedData} rather than a config file because that is what it is: state produced by
 * play, belonging to one world, that must round-trip without an operator editing anything. A config
 * file would additionally have to be reconciled with what players had already done.
 *
 * <h2>What an entry means</h2>
 * "A station entered at this host was authorised by an operator." It is <em>not</em> "only this host
 * may be contacted": a station URL is normally a playlist naming an endpoint on another domain, so
 * the authorisation has to cover the whole resolution chain — see {@code RadioServer.egressGuardFor}.
 *
 * <h2>What this does not do</h2>
 * It never relaxes the address-range checks. Loopback, RFC1918, CGNAT and link-local stay refused on
 * every hop for every station, authorised or not; that separation is the whole point of ADR-0011
 * keeping range blocking as defence in depth rather than as the primary control. Authorising
 * {@code example.com} cannot reach {@code 169.254.169.254}, even if {@code example.com} resolves
 * there or redirects to it.
 */
public final class RadioAllowlist extends SavedData {

    /** Bounds the file, and a server that has legitimately authorised this many has a config problem. */
    private static final int MAX_HOSTS = 256;

    private static final String FILE_ID = "mmmm_allowlist";
    private static final String KEY_HOSTS = "Hosts";

    private final Set<String> hosts = new LinkedHashSet<>();

    public static RadioAllowlist load(CompoundTag tag) {
        RadioAllowlist data = new RadioAllowlist();
        ListTag list = tag.getList(KEY_HOSTS, Tag.TAG_STRING);
        for (int i = 0; i < list.size() && data.hosts.size() < MAX_HOSTS; i++) {
            String host = normalise(list.getString(i));
            if (!host.isEmpty()) {
                data.hosts.add(host);
            }
        }
        return data;
    }

    /**
     * The world's allowlist, created on first use.
     *
     * <p>Stored on the overworld's data storage because it is server-wide, not per-dimension. A radio
     * in the Nether and one in the overworld are the same server making the same outbound request.
     */
    public static RadioAllowlist get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(RadioAllowlist::load, RadioAllowlist::new, FILE_ID);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (String host : hosts) {
            list.add(StringTag.valueOf(host));
        }
        tag.put(KEY_HOSTS, list);
        return tag;
    }

    /**
     * Authorises a host.
     *
     * @return false if the list is full, in which case nothing was added
     */
    public boolean authorise(String host) {
        String normalised = normalise(host);
        if (normalised.isEmpty() || hosts.contains(normalised)) {
            return !normalised.isEmpty();
        }
        if (hosts.size() >= MAX_HOSTS) {
            return false;
        }
        hosts.add(normalised);
        setDirty();
        return true;
    }

    public boolean isAuthorised(String host) {
        return hosts.contains(normalise(host));
    }

    public Set<String> hosts() {
        return Set.copyOf(hosts);
    }

    private static String normalise(String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }
}
