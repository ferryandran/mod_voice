package com.voicedoor.block;

import com.voicedoor.VoiceDoorMod;
import com.voicedoor.blockentity.VoiceDoorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * VoiceDoor Block - Pintu dua blok (atas dan bawah) yang hanya terbuka dengan verifikasi suara.
 *
 * Properties:
 * - FACING: Arah pintu menghadap (NORTH/SOUTH/EAST/WEST)
 * - OPEN: Apakah pintu terbuka
 * - HINGE: Posisi engsel (LEFT/RIGHT)
 * - HALF: Bagian pintu (UPPER/LOWER)
 * - POWERED: Apakah ditenagai redstone
 */
public class VoiceDoorBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final EnumProperty<DoorHingeSide> HINGE = BlockStateProperties.DOOR_HINGE;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    // VoxelShapes untuk collision - sama seperti door vanilla
    protected static final VoxelShape SOUTH_AABB = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 3.0D);
    protected static final VoxelShape NORTH_AABB = Block.box(0.0D, 0.0D, 13.0D, 16.0D, 16.0D, 16.0D);
    protected static final VoxelShape WEST_AABB = Block.box(13.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
    protected static final VoxelShape EAST_AABB = Block.box(0.0D, 0.0D, 0.0D, 3.0D, 16.0D, 16.0D);

    public VoiceDoorBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(OPEN, false)
                .setValue(HINGE, DoorHingeSide.LEFT)
                .setValue(POWERED, false)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN, HINGE, POWERED, HALF);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() < level.getMaxBuildHeight() - 1 && level.getBlockState(pos.above()).canBeReplaced(context)) {
            boolean powered = level.hasNeighborSignal(pos) || level.hasNeighborSignal(pos.above());
            return this.defaultBlockState()
                    .setValue(FACING, context.getHorizontalDirection())
                    .setValue(HINGE, this.getHinge(context))
                    .setValue(POWERED, powered)
                    .setValue(OPEN, powered)
                    .setValue(HALF, DoubleBlockHalf.LOWER);
        }
        return null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        // Pasang blok bagian atas pintu
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), 3);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        boolean open = state.getValue(OPEN);
        boolean rightHinge = state.getValue(HINGE) == DoorHingeSide.RIGHT;

        if (!open) {
            return switch (facing) {
                case EAST -> EAST_AABB;
                case SOUTH -> SOUTH_AABB;
                case WEST -> WEST_AABB;
                default -> NORTH_AABB;
            };
        } else {
            return switch (facing) {
                case EAST -> rightHinge ? SOUTH_AABB : NORTH_AABB;
                case SOUTH -> rightHinge ? WEST_AABB : EAST_AABB;
                case WEST -> rightHinge ? NORTH_AABB : SOUTH_AABB;
                default -> rightHinge ? EAST_AABB : WEST_AABB;
            };
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            // Client side: animasi saja, server yang memutuskan
            return InteractionResult.SUCCESS;
        }

        // Dapatkan blok bawah (lower half) untuk block entity
        BlockPos lowerPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        BlockEntity be = level.getBlockEntity(lowerPos);
        if (!(be instanceof VoiceDoorBlockEntity voiceDoorBE)) {
            return InteractionResult.PASS;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        // Delegasikan ke block entity untuk verifikasi suara
        voiceDoorBE.handlePlayerInteraction(serverPlayer, lowerPos, level);
        return InteractionResult.CONSUME;
    }

    /**
     * Buka atau tutup pintu (kedua blok sekaligus).
     */
    public void setOpen(@Nullable Player player, Level level, BlockState state, BlockPos pos, boolean open) {
        BlockState newState = state.setValue(OPEN, open);
        level.setBlock(pos, newState, 10);

        // Update blok pasangan (atas/bawah)
        BlockPos otherPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        BlockState otherState = level.getBlockState(otherPos);
        if (otherState.getBlock() == this) {
            level.setBlock(otherPos, otherState.setValue(OPEN, open), 10);
        }

        // Mainkan suara
        this.playSound(player, level, pos, open);
    }

    protected void playSound(@Nullable Player player, Level level, BlockPos pos, boolean open) {
        level.playSound(player, pos,
                open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE,
                SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            // Hapus juga blok pasangan
            BlockPos otherPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState otherState = level.getBlockState(otherPos);
            if (otherState.getBlock() == this && otherState.getValue(HALF) != state.getValue(HALF)) {
                level.setBlock(otherPos, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(null, 2001, otherPos, Block.getId(otherState));
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Hanya buat block entity untuk lower half
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) {
            return new VoiceDoorBlockEntity(pos, state);
        }
        return null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (state.getValue(HALF) != DoubleBlockHalf.LOWER) return null;
        return createTickerHelper(blockEntityType, com.voicedoor.blockentity.ModBlockEntities.VOICE_DOOR.get(),
                VoiceDoorBlockEntity::tick);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    private DoorHingeSide getHinge(BlockPlaceContext context) {
        BlockGetter blockgetter = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        Direction direction = context.getHorizontalDirection();
        BlockPos abovePos = clickedPos.above();
        Direction counterClockwise = direction.getCounterClockWise();
        BlockPos counterClockwisePos = clickedPos.relative(counterClockwise);

        boolean counterClockwiseSolid = blockgetter.getBlockState(counterClockwisePos).isCollisionShapeFullBlock(blockgetter, counterClockwisePos)
                || blockgetter.getBlockState(counterClockwisePos.above()).isCollisionShapeFullBlock(blockgetter, counterClockwisePos.above());

        Direction clockwise = direction.getClockWise();
        BlockPos clockwisePos = clickedPos.relative(clockwise);
        boolean clockwiseSolid = blockgetter.getBlockState(clockwisePos).isCollisionShapeFullBlock(blockgetter, clockwisePos)
                || blockgetter.getBlockState(clockwisePos.above()).isCollisionShapeFullBlock(blockgetter, clockwisePos.above());

        int hinge = (counterClockwiseSolid ? 1 : 0) - (clockwiseSolid ? 1 : 0);
        if (hinge <= 0) {
            float clickX = (float)(context.getClickLocation().x - (double)clickedPos.getX());
            float clickZ = (float)(context.getClickLocation().z - (double)clickedPos.getZ());
            return ((direction == Direction.NORTH) != (clickZ < 0.5F))
                    || ((direction == Direction.EAST) == (clickX < 0.5F))
                    || ((direction == Direction.SOUTH) == (clickZ < 0.5F))
                    || ((direction == Direction.WEST) != (clickX < 0.5F))
                    ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
        }
        return DoorHingeSide.LEFT;
    }
}
