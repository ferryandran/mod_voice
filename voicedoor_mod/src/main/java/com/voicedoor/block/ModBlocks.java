package com.voicedoor.block;

import com.voicedoor.VoiceDoorMod;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, VoiceDoorMod.MOD_ID);

    /**
     * VoiceDoor - secara visual menggunakan model garage_silver_door dari Macaw's Doors
     * (mcwdoors:block/garage_silver_door_*). Logika block entity tetap milik mod ini.
     * Pastikan mod mcwdoors terpasang agar model tampil dengan benar.
     */
    public static final RegistryObject<VoiceDoorBlock> VOICE_DOOR = BLOCKS.register("voice_door",
            () -> new VoiceDoorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(5.0F, 10.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .requiresCorrectToolForDrops()));


    /**
     * Papan Logo Kopdes — panel 3D 5x5 blok yang nempel ke dinding.
     * Rendering via KopdesLogoRenderer (BlockEntity), mesh dibangun dari kopdes_logo_3d.png.
     * Ganti texture: assets/voicedoor/textures/block/kopdes_logo.png
     * lalu jalankan lagi: python generate_3d_mesh.py
     */
    public static final RegistryObject<KopdesLogoBlock> KOPDES_LOGO = BLOCKS.register("kopdes_logo",
            () -> new KopdesLogoBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.STONE)
                    .noOcclusion()));
}
