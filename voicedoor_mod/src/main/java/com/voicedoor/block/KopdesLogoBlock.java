package com.voicedoor.block;

import com.voicedoor.blockentity.KopdesLogoBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * Papan Logo Kopdes — 1 blok anchor di dinding,
 * BER menggambar panel logo 5×5 blok rata permukaan dinding.
 *
 * FACING = arah muka panel (arah sisi dinding yang diklik).
 * Dinding harus ada di belakang blok (arah FACING.opposite()).
 */
public class KopdesLogoBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    // Tipis 2px, menempel ke dinding
    private static final VoxelShape N = Block.box(0, 0, 14, 16, 16, 16);
    private static final VoxelShape S = Block.box(0, 0,  0, 16, 16,  2);
    private static final VoxelShape W = Block.box(14,0,  0, 16, 16, 16);
    private static final VoxelShape E = Block.box(0, 0,  0,  2, 16, 16);

    public KopdesLogoBlock(Properties p) {
        super(p);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction face = ctx.getClickedFace();
        return face.getAxis().isHorizontal()
                ? defaultBlockState().setValue(FACING, face)
                : defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return switch (s.getValue(FACING)) {
            case NORTH -> N; case SOUTH -> S;
            case WEST  -> W; default    -> E;
        };
    }

    @Override
    public VoxelShape getCollisionShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return net.minecraft.world.phys.shapes.Shapes.empty();
    }

    @Override public RenderShape getRenderShape(BlockState s) { return RenderShape.ENTITYBLOCK_ANIMATED; }

    @Override
    public boolean canSurvive(BlockState s, LevelReader l, BlockPos p) {
        Direction f = s.getValue(FACING);
        BlockPos wp = p.relative(f.getOpposite());
        return l.getBlockState(wp).isFaceSturdy(l, wp, f);
    }

    @Override
    public BlockState updateShape(BlockState s, Direction d, BlockState ns,
                                  LevelAccessor l, BlockPos p, BlockPos np) {
        if (d == s.getValue(FACING).getOpposite() && !s.canSurvive(l, p))
            return Blocks.AIR.defaultBlockState();
        return super.updateShape(s, d, ns, l, p, np);
    }

    @Nullable @Override
    public BlockEntity newBlockEntity(BlockPos p, BlockState s) { return new KopdesLogoBlockEntity(p, s); }

    @Nullable @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            net.minecraft.world.level.Level l, BlockState s, BlockEntityType<T> t) { return null; }
}
