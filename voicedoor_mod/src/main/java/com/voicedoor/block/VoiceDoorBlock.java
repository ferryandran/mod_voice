package com.voicedoor.block;

import com.voicedoor.blockentity.ModBlockEntities;
import com.voicedoor.blockentity.VoiceDoorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * Pintu dua blok yang hanya terbuka setelah verifikasi suara.
 *
 * <p>Properti: {@code FACING}, {@code OPEN}, {@code HINGE}, {@code HALF}.
 *
 * <p><b>Catatan soal redstone.</b> Versi sebelumnya punya properti {@code POWERED} tapi
 * tidak pernah mengimplementasikan {@code neighborChanged}, jadi properti itu mati total -
 * hanya melipatgandakan jumlah blockstate tanpa efek. Properti itu sekarang dihapus, dan
 * itu memang perilaku yang diinginkan: pintu yang bisa dibuka comparator atau tombol
 * bukan kunci suara. Satu-satunya jalan membuka pintu ini adalah verifikasi suara.
 *
 * <p>Perbaikan lain dibanding versi sebelumnya: {@link #canSurvive} mencegah pintu
 * melayang dan {@link #updateShape} menghancurkan separuh yang tertinggal saat pasangannya
 * hilang. Tanpa keduanya, ledakan atau piston bisa meninggalkan separuh pintu menggantung
 * dengan state yang tidak sinkron.
 */
public class VoiceDoorBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final EnumProperty<DoorHingeSide> HINGE = BlockStateProperties.DOOR_HINGE;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    // Bentuk collision - sama seperti pintu vanilla (tebal 3/16 blok).
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
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN, HINGE, HALF);
    }

    // -----------------------------------------------------------------------
    // Penempatan
    // -----------------------------------------------------------------------
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        boolean roomAbove = pos.getY() < level.getMaxBuildHeight() - 1
                && level.getBlockState(pos.above()).canBeReplaced(context);
        if (!roomAbove) {
            return null;
        }
        // Pintu harus berdiri di atas sesuatu, seperti pintu vanilla.
        if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                && !level.getBlockState(pos.below()).is(this)) {
            return null;
        }
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection())
                .setValue(HINGE, this.getHinge(context))
                .setValue(HALF, DoubleBlockHalf.LOWER);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    // -----------------------------------------------------------------------
    // Integritas struktur
    // -----------------------------------------------------------------------
    /**
     * Separuh bawah butuh penopang di bawahnya; separuh atas butuh separuh bawah.
     *
     * <p>Tanpa ini pintu bisa melayang setelah blok di bawahnya ditambang.
     */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) {
            return belowState.isFaceSturdy(level, below, Direction.UP);
        }
        return belowState.is(this) && belowState.getValue(HALF) == DoubleBlockHalf.LOWER;
    }

    /**
     * Jaga kedua separuh tetap sinkron, dan hancurkan separuh yang tertinggal.
     *
     * <p>Dipanggil saat blok tetangga berubah - termasuk saat pasangannya dihancurkan
     * piston, ledakan, atau perintah {@code /setblock}.
     */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        DoubleBlockHalf half = state.getValue(HALF);
        boolean towardsPartner = (direction == Direction.UP && half == DoubleBlockHalf.LOWER)
                || (direction == Direction.DOWN && half == DoubleBlockHalf.UPPER);

        if (towardsPartner) {
            if (neighborState.is(this) && neighborState.getValue(HALF) != half) {
                // Pasangan masih ada: ikuti state-nya supaya animasi tidak pecah.
                return state.setValue(FACING, neighborState.getValue(FACING))
                        .setValue(OPEN, neighborState.getValue(OPEN))
                        .setValue(HINGE, neighborState.getValue(HINGE));
            }
            return Blocks.AIR.defaultBlockState();
        }

        if (half == DoubleBlockHalf.LOWER && direction == Direction.DOWN
                && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    /**
     * Saat pemain menambang satu separuh, hilangkan juga pasangannya tanpa drop kedua.
     *
     * <p>Vanilla memakai hook ini alih-alih {@code onRemove} supaya loot table dipanggil
     * tepat satu kali.
     */
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            // Hapus pasangannya tanpa drop: loot table separuh yang ditambang sudah
            // menjatuhkan satu item, jadi menjatuhkan dua akan menggandakan item.
            DoubleBlockHalf half = state.getValue(HALF);
            BlockPos partnerPos = half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState partner = level.getBlockState(partnerPos);
            if (partner.is(this) && partner.getValue(HALF) != half) {
                level.setBlock(partnerPos, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, partnerPos, Block.getId(partner));
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    // -----------------------------------------------------------------------
    // Bentuk
    // -----------------------------------------------------------------------
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
        }
        return switch (facing) {
            case EAST -> rightHinge ? SOUTH_AABB : NORTH_AABB;
            case SOUTH -> rightHinge ? WEST_AABB : EAST_AABB;
            case WEST -> rightHinge ? NORTH_AABB : SOUTH_AABB;
            default -> rightHinge ? EAST_AABB : WEST_AABB;
        };
    }

    // -----------------------------------------------------------------------
    // Interaksi
    // -----------------------------------------------------------------------
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        BlockPos lowerPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        if (!(level.getBlockEntity(lowerPos) instanceof VoiceDoorBlockEntity door)) {
            return InteractionResult.PASS;
        }

        door.handlePlayerInteraction(serverPlayer, lowerPos, level);
        return InteractionResult.CONSUME;
    }

    /** Buka atau tutup kedua separuh sekaligus. */
    public void setOpen(@Nullable Player player, Level level, BlockState state, BlockPos pos, boolean open) {
        if (!state.is(this) || state.getValue(OPEN) == open) {
            return;
        }
        level.setBlock(pos, state.setValue(OPEN, open), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);

        DoubleBlockHalf half = state.getValue(HALF);
        BlockPos partnerPos = half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        BlockState partner = level.getBlockState(partnerPos);
        if (partner.is(this) && partner.getValue(HALF) != half) {
            level.setBlock(partnerPos, partner.setValue(OPEN, open),
                    Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
        }

        playDoorSound(player, level, pos, open);
    }

    protected void playDoorSound(@Nullable Player player, Level level, BlockPos pos, boolean open) {
        level.playSound(player, pos,
                open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE,
                SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
    }

    // -----------------------------------------------------------------------
    // Block entity
    // -----------------------------------------------------------------------
    /**
     * Hanya separuh bawah punya block entity; separuh atas mendelegasikan ke bawah.
     * Ini menghindari dua salinan state pemilik untuk satu pintu.
     */
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER
                ? new VoiceDoorBlockEntity(pos, state)
                : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide || state.getValue(HALF) != DoubleBlockHalf.LOWER) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.VOICE_DOOR.get(), VoiceDoorBlockEntity::tick);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // -----------------------------------------------------------------------
    // Rotasi / cermin
    // -----------------------------------------------------------------------
    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return mirror == Mirror.NONE
                ? state
                : state.rotate(mirror.getRotation(state.getValue(FACING))).cycle(HINGE);
    }

    @Override
    public SoundType getSoundType(BlockState state) {
        return SoundType.METAL;
    }

    // -----------------------------------------------------------------------
    // Engsel
    // -----------------------------------------------------------------------
    /** Pilih sisi engsel seperti pintu vanilla: berdasarkan blok tetangga dan titik klik. */
    private DoorHingeSide getHinge(BlockPlaceContext context) {
        BlockGetter level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        Direction facing = context.getHorizontalDirection();

        Direction counterClockwise = facing.getCounterClockWise();
        BlockPos ccwPos = clickedPos.relative(counterClockwise);
        boolean ccwSolid = level.getBlockState(ccwPos).isCollisionShapeFullBlock(level, ccwPos)
                || level.getBlockState(ccwPos.above()).isCollisionShapeFullBlock(level, ccwPos.above());

        Direction clockwise = facing.getClockWise();
        BlockPos cwPos = clickedPos.relative(clockwise);
        boolean cwSolid = level.getBlockState(cwPos).isCollisionShapeFullBlock(level, cwPos)
                || level.getBlockState(cwPos.above()).isCollisionShapeFullBlock(level, cwPos.above());

        boolean ccwIsDoor = level.getBlockState(ccwPos).is(this);
        boolean cwIsDoor = level.getBlockState(cwPos).is(this);

        // Pintu bersebelahan: buka ke arah berlawanan supaya membentuk pintu ganda.
        if (ccwIsDoor && !cwIsDoor) return DoorHingeSide.RIGHT;
        if (cwIsDoor && !ccwIsDoor) return DoorHingeSide.LEFT;

        int bias = (ccwSolid ? 1 : 0) - (cwSolid ? 1 : 0);
        if (bias != 0) {
            return bias > 0 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
        }

        double clickX = context.getClickLocation().x - clickedPos.getX();
        double clickZ = context.getClickLocation().z - clickedPos.getZ();
        return switch (facing) {
            case NORTH -> clickX > 0.5D ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
            case SOUTH -> clickX < 0.5D ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
            case WEST -> clickZ < 0.5D ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
            default -> clickZ > 0.5D ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
        };
    }
}
