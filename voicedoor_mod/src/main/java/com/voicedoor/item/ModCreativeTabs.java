package com.voicedoor.item;

import com.voicedoor.VoiceDoorMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Tab creative mode untuk mod ini.
 *
 * <p>Sebelumnya kunci terjemahan {@code itemGroup.voicedoor} sudah ada di file bahasa tapi
 * tidak ada tab yang pernah didaftarkan, sehingga blok VoiceDoor hanya bisa didapat lewat
 * crafting atau {@code /give} - tidak muncul di inventory creative sama sekali.
 *
 * <p>Di 1.20.1 tab creative adalah registry entry, bukan lagi {@code CreativeModeTab.builder}
 * statis seperti versi lama.
 */
public final class ModCreativeTabs {

    // Tab creative ada di registry vanilla (Registries.CREATIVE_MODE_TAB), bukan di
    // ForgeRegistries - tidak ada ForgeRegistries.CREATIVE_MODE_TABS di 1.20.1.
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, VoiceDoorMod.MOD_ID);

    public static final RegistryObject<CreativeModeTab> VOICEDOOR_TAB = TABS.register("voicedoor",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.voicedoor"))
                    .icon(() -> new ItemStack(ModItems.VOICE_DOOR.get()))
                    .displayItems((params, output) -> output.accept(ModItems.VOICE_DOOR.get()))
                    .build());

    private ModCreativeTabs() {
    }
}
