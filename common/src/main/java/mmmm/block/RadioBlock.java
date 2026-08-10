package mmmm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The radio. For now it is furniture: it faces the player who placed it and nothing else.
 *
 * <p>Playback, the block entity and the station GUI all arrive in milestone 4b. Keeping this
 * milestone to a block with no behaviour is deliberate — it isolates "does the build, the mod
 * loading, the registries and the asset pipeline work at all" from the genuinely risky sound-engine
 * work that follows.
 *
 * <p>The model, texture and collision shapes are ported from an earlier Forge 1.18.2 prototype. The
 * class is loader-agnostic and so lives in {@code common/} (ADR-0002); registration is per-loader.
 */
public class RadioBlock extends HorizontalDirectionalBlock {

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
