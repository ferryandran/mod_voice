package com.voicedoor.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public class KopdesLogoBlockEntity extends BlockEntity {
    public KopdesLogoBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.KOPDES_LOGO.get(), pos, state);
    }

    /** Panel logo 5x5 blok berpusat di blok ini (lihat KopdesLogoRenderer). */
    @Override
    public AABB getRenderBoundingBox() {
        return new AABB(getBlockPos()).inflate(2.5);
    }
}
