package mmmm.block;

import mmmm.MmmmContent;
import mmmm.Stations;
import mmmm.server.RadioServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Consumer;

/**
 * The radio: right-click to play, sneak-right-click to change station.
 *
 * <p>The model, texture and collision shapes are ported from an earlier Forge 1.18.2 prototype. The
 * class is loader-agnostic and so lives in {@code common/} (ADR-0002); registration is per-loader.
 */
public class RadioBlock extends HorizontalDirectionalBlock implements EntityBlock {

    private static final VoxelShape SHAPE_NORTH = makeShapeNorth();
    private static final VoxelShape SHAPE_SOUTH = makeShapeSouth();
    private static final VoxelShape SHAPE_EAST = makeShapeEast();
    private static final VoxelShape SHAPE_WEST = makeShapeWest();

    public RadioBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case EAST -> SHAPE_EAST;
            case WEST -> SHAPE_WEST;
            // FACING is horizontal, so these are unreachable — but the compiler wants every
            // Direction covered, and a full cube is the harmless answer.
            case UP, DOWN -> Shapes.block();
        };
    }

    // ------------------------------------------------------------------ behaviour

    /**
     * What the client does with a radio each tick.
     *
     * <p>Installed by the client rather than called directly, so that nothing on this class's
     * constant pool leads to {@code net.minecraft.client}. A dedicated server that loads a client
     * class dies immediately and with a stack trace that points anywhere but here, and this block is
     * on the server's classpath by construction.
     */
    private static Consumer<RadioBlockEntity> clientTicker = be -> { };

    public static void setClientTicker(Consumer<RadioBlockEntity> ticker) {
        clientTicker = ticker;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RadioBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (type != MmmmContent.radioBlockEntity()) {
            return null;
        }
        return level.isClientSide
                ? (lvl, pos, st, be) -> clientTicker.accept((RadioBlockEntity) be)
                : (lvl, pos, st, be) -> RadioServer.tickBlock((RadioBlockEntity) be);
    }

    /**
     * Right-click toggles playback; sneak-right-click steps to the next station.
     *
     * <p>Everything happens server-side: the block entity is the authority on what is playing, and
     * its change propagates to clients through the normal block-update path (master plan §5.2).
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof RadioBlockEntity radio)) {
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) {
            Stations.Station next = Stations.next(radio.getStation());
            radio.setStation(next.url());
            // Changing station mid-play has to drop the old relay claim and take a new one, which
            // RadioServer does by noticing the station no longer matches the one it is holding.
            player.displayClientMessage(Component.literal(next.name()), true);
        } else {
            radio.setPlaying(!radio.isPlaying());
            player.displayClientMessage(Component.literal(radio.isPlaying()
                    ? Stations.displayName(radio.getStation())
                    : "Off"), true);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Releases the upstream connection when the block goes away.
     *
     * <p>Without this the relay thread outlives every block that referenced it and the server sits
     * as a phantom listener on the station forever.
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof RadioBlockEntity radio) {
            RadioServer.blockRemoved(radio);
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    // The shape depends only on the BlockState, so vanilla's per-state shape cache is correct here
    // and Properties.dynamicShape() must NOT be set — it would disable that cache for nothing.
    //
    // Each rotation is transcribed by hand from the Blockbench model (art/radio.bbmodel): one body
    // box plus the three buttons on top. Verbose, but it matches the model exactly, and 1.20.1 has
    // no built-in VoxelShape rotation to derive the other three from north.

    private static VoxelShape makeShapeNorth() {
        VoxelShape shape = Shapes.empty();
        shape = Shapes.join(shape, Shapes.box(0.125, 0, 0.25, 0.875, 0.5, 0.5625), BooleanOp.OR);
        shape = Shapes.join(shape, Shapes.box(0.75, 0.5, 0.375, 0.8125, 0.5625, 0.4375), BooleanOp.OR);
        shape = Shapes.join(shape, Shapes.box(0.625, 0.5, 0.375, 0.6875, 0.5625, 0.4375), BooleanOp.OR);
        shape = Shapes.join(shape, Shapes.box(0.5, 0.5, 0.375, 0.5625, 0.5625, 0.4375), BooleanOp.OR);
        return shape;
    }

    private static VoxelShape makeShapeSouth() {
        VoxelShape shape = Shapes.empty();
        shape = Shapes.join(shape, Shapes.box(0.125, 0, 0.4375, 0.875, 0.5, 0.75), BooleanOp.OR);
        shape = Shapes.join(shape, Shapes.box(0.1875, 0.5, 0.5625, 0.25, 0.5625, 0.625), BooleanOp.OR);
        shape = Shapes.join(shape, Shapes.box(0.3125, 0.5, 0.5625, 0.375, 0.5625, 0.625), BooleanOp.OR);
        shape = Shapes.join(shape, Shapes.box(0.4375, 0.5, 0.5625, 0.5, 0.5625, 0.625), BooleanOp.OR);
        return shape;
    }

    private static VoxelShape makeShapeEast() {
        VoxelShape shape = Shapes.empty();
        shape = Shapes.join(shape, Shapes.box(0.4375, 0, 0.125, 0.75, 0.5, 0.875), BooleanOp.OR);
        shape = Shapes.join(shape, Shapes.box(0.5625, 0.5, 0.75, 0.625, 0.5625, 0.8125), BooleanOp.OR);
        shape = Shapes.join(shape, Shapes.box(0.5625, 0.5, 0.625, 0.625, 0.5625, 0.6875), BooleanOp.OR);
        shape = Shapes.join(shape, Shapes.box(0.5625, 0.5, 0.5, 0.625, 0.5625, 0.5625), BooleanOp.OR);
        return shape;
    }

    private static VoxelShape makeShapeWest() {
        VoxelShape shape = Shapes.empty();
        shape = Shapes.join(shape, Shapes.box(0.25, 0, 0.125, 0.5625, 0.5, 0.875), BooleanOp.OR);
        shape = Shapes.join(shape, Shapes.box(0.375, 0.5, 0.1875, 0.4375, 0.5625, 0.25), BooleanOp.OR);
        shape = Shapes.join(shape, Shapes.box(0.375, 0.5, 0.3125, 0.4375, 0.5625, 0.375), BooleanOp.OR);
        shape = Shapes.join(shape, Shapes.box(0.375, 0.5, 0.4375, 0.4375, 0.5625, 0.5), BooleanOp.OR);
        return shape;
    }
}
