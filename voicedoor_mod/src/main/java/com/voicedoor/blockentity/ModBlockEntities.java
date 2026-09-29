package com.voicedoor.blockentity;

import com.voicedoor.VoiceDoorMod;
import com.voicedoor.block.ModBlocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, VoiceDoorMod.MOD_ID);

    public static final RegistryObject<BlockEntityType<VoiceDoorBlockEntity>> VOICE_DOOR =
            BLOCK_ENTITIES.register("voice_door",
                    () -> BlockEntityType.Builder.of(VoiceDoorBlockEntity::new,
                            ModBlocks.VOICE_DOOR.get()).build(null));
}
