package mmmm.item;

import mmmm.MmmmContent;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * Converts vanilla blocks into their 4M counterparts on right-click.
 *
 * <p>The conversion table is not here. It names blocks this mod registers, and registration is
 * loader API (ADR-0002), so it arrives through {@link MmmmContent} — the same seam the radio's
 * block entity type uses.
 */
public class DepolarizationHammerItem extends Item {

    public DepolarizationHammerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();

        Block converted = MmmmContent.depolarized(level.getBlockState(pos).getBlock());
        if (converted == null) {
            // PASS, not FAIL. Almost every right-click hits something the hammer does not convert,
            // and FAIL would swallow the off-hand's turn — you could not place a torch while
            // holding the hammer.
            return InteractionResult.PASS;
        }

        // Spawn protection and the world border. ServerPlayerGameMode checks these before breaking
        // a block but not before an item's useOn, so an unguarded converter is a protection bypass:
        // gold blocks inside spawn could be turned into something else entirely.
        if (player == null || !level.mayInteract(player, pos)) {
            return InteractionResult.PASS;
        }

        // Everything past here mutates the world, so it is server-only. The client still reports
        // success so the arm swings on this frame rather than after a round trip; it sees the same
        // block state, so its prediction cannot disagree with what the server decides.
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockState newState = converted.defaultBlockState();
        level.setBlock(pos, newState, Block.UPDATE_ALL);

        // Level event 2001 is vanilla's block-break effect. Firing it from the server broadcasts the
        // particles and the new block's break sound to everyone in range. Level#addParticle here
        // would show them to nobody instead: it is a no-op outside a ClientLevel, and this branch is
        // by definition not one.
        level.levelEvent(2001, pos, Block.getId(newState));
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.8F, 1.4F);

        // Sculk sensors and the warden should hear this like any other block edit.
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);

        // No creative-mode branch needed: hurtAndBreak already no-ops for a player with instabuild.
        InteractionHand hand = context.getHand();
        context.getItemInHand().hurtAndBreak(1, player, held -> held.broadcastBreakEvent(hand));

        return InteractionResult.CONSUME;
    }
}
