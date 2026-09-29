package com.voicedoor.item;

import com.voicedoor.VoiceDoorMod;
import com.voicedoor.block.ModBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, VoiceDoorMod.MOD_ID);

    // Item untuk voice door block
    public static final RegistryObject<BlockItem> VOICE_DOOR = ITEMS.register("voice_door",
            () -> new BlockItem(ModBlocks.VOICE_DOOR.get(),
                    new Item.Properties().stacksTo(1)));
}
