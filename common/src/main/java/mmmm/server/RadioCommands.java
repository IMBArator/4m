package mmmm.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import mmmm.block.RadioBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /mmmm stop [radius]} — switch off every radio playing nearby.
 *
 * <p>The one thing an operator needs that the control screen cannot give them: the screen acts on
 * one radio, and the problem it does not solve is somebody having left several playing where you
 * would rather they were not. Walking to each one and right-clicking it is not an administrative
 * tool.
 *
 * <p>Named {@code /mmmm} rather than {@code /4m} because Brigadier parses it, which puts it on the
 * identifier side of ADR-0012's rule.
 *
 * <p>Loader-neutral: Brigadier and {@code net.minecraft.commands} are Minecraft, not loader API, so
 * this belongs in {@code common/} (ADR-0002). Only the registration hook is per-loader.
 */
public final class RadioCommands {

    /**
     * Four times the 16-block audible range, so "nearby" comfortably covers everything the person
     * running the command could actually be hearing, plus whatever is behind the next wall.
     */
    private static final int DEFAULT_RADIUS = 64;

    /**
     * A bound, not a policy. The scan visits every loaded chunk in range, and 256 blocks is already
     * 1089 chunks — past that the command stops being "stop the racket near me" and turns into a
     * whole-world sweep, which wants deliberate design rather than a large number typed in a hurry.
     */
    private static final int MAX_RADIUS = 256;

    private RadioCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mmmm")
                // Level 2 is the operator bar, and it is the whole permission model here: stopping
                // someone else's radio is an administrative act, and there is nothing on this
                // command a non-operator should reach.
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("stop")
                        .executes(ctx -> stopNearby(ctx.getSource(), DEFAULT_RADIUS))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                                .executes(ctx -> stopNearby(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));
    }

    /**
     * Server thread — it mutates block entities.
     *
     * @return how many radios were stopped, which is what {@code execute store result} sees
     */
    private static int stopNearby(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();

        List<RadioBlockEntity> playing = findPlayingWithin(level, origin, radius);
        if (playing.isEmpty()) {
            // sendFailure, not a zero-count success: this is the vanilla convention for "the command
            // matched nothing" (compare /kill with no targets), and it is what makes the difference
            // visible to `execute if`.
            source.sendFailure(Component.literal(
                    "No radio is playing within " + radius + " blocks."));
            return 0;
        }

        for (RadioBlockEntity radio : playing) {
            // Only the flag. RadioServer.tickBlock sees !isPlaying() on its next tick and drops the
            // block's claim on the relay session, closing the upstream connection when the last
            // block lets go. Releasing it here as well would be a second path to the same state and
            // a chance for the two to disagree.
            radio.setPlaying(false);
        }

        String where = playing.size() == 1
                ? " at " + describe(playing.get(0).getBlockPos())
                : "";
        int count = playing.size();
        // true: an operator silencing other people's radios is worth showing to the other operators,
        // which is exactly what the broadcast flag is for.
        source.sendSuccess(() -> Component.literal(
                "Stopped " + count + (count == 1 ? " radio" : " radios") + where + "."), true);
        return count;
    }

    /**
     * Every playing radio within {@code radius} of {@code origin}, in loaded chunks.
     *
     * <p>Collected before anything is switched off rather than stopped in place: mutating a block
     * entity while walking a chunk's block entity map is the kind of thing that works until it does
     * not.
     *
     * <p>Unloaded chunks are skipped and not loaded. Nothing there can be audible — a block entity
     * that has stopped ticking has already dropped its relay claim — so forcing chunks into memory
     * would search where the answer is known to be "no", at the cost of loading arbitrary chunks
     * from a command. A radio there keeps its saved {@code playing} flag and resumes when its chunk
     * comes back, which is the flag doing its job.
     */
    private static List<RadioBlockEntity> findPlayingWithin(ServerLevel level, Vec3 origin, int radius) {
        List<RadioBlockEntity> found = new ArrayList<>();
        double limit = (double) radius * radius;

        int minChunkX = SectionPos.blockToSectionCoord(Mth.floor(origin.x) - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(Mth.floor(origin.x) + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(Mth.floor(origin.z) - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(Mth.floor(origin.z) + radius);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof RadioBlockEntity radio
                            && radio.isPlaying()
                            && withinRadius(radio.getBlockPos(), origin, limit)) {
                        found.add(radio);
                    }
                }
            }
        }
        return found;
    }

    /** Measured from the block's centre, matching how {@code RadioServer} decides who can hear it. */
    private static boolean withinRadius(BlockPos pos, Vec3 origin, double limitSquared) {
        double dx = pos.getX() + 0.5 - origin.x;
        double dy = pos.getY() + 0.5 - origin.y;
        double dz = pos.getZ() + 0.5 - origin.z;
        return dx * dx + dy * dy + dz * dz <= limitSquared;
    }

    private static String describe(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
