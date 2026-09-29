package com.voicedoor;

import com.voicedoor.block.ModBlocks;
import com.voicedoor.blockentity.ModBlockEntities;
import com.voicedoor.command.VoiceDoorCommands;
import com.voicedoor.item.ModItems;
import com.voicedoor.network.ModNetwork;
import com.voicedoor.voice.VoiceChatPlugin;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * VoiceDoor Mod - Pintu custom yang hanya bisa dibuka oleh suara pemilik rumah.
 *
 * Alur kerja:
 * 1. Pemain menaruh VoiceDoor block
 * 2. Pemain menjalankan /voicedoor register <nama> - memulai rekaman suara via Simple Voice Chat
 * 3. Suara dikirim ke Python API Server (ECAPA-TDNN)
 * 4. Setelah minimal 2 member terdaftar, jalankan /voicedoor train
 * 5. Saat mendekati pintu, suara pemain di-capture secara otomatis dan diverifikasi
 * 6. Pintu hanya terbuka jika suara cocok dengan pemilik yang terdaftar
 */
@Mod(VoiceDoorMod.MOD_ID)
public class VoiceDoorMod {
    public static final String MOD_ID = "voicedoor";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static boolean VOICE_CHAT_AVAILABLE = false;

    public VoiceDoorMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register blocks, items, block entities
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Check apakah Simple Voice Chat tersedia
        VOICE_CHAT_AVAILABLE = ModList.get().isLoaded("voicechat");
        if (VOICE_CHAT_AVAILABLE) {
            LOGGER.info("[VoiceDoor] Simple Voice Chat ditemukan! Integrasi voice aktif.");
        } else {
            LOGGER.warn("[VoiceDoor] Simple Voice Chat TIDAK ditemukan. Mod berjalan dalam mode terbatas.");
        }

        // Inisialisasi network packets
        ModNetwork.register();
        LOGGER.info("[VoiceDoor] Network packets terdaftar.");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("[VoiceDoor] Client setup selesai.");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[VoiceDoor] Server starting...");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        VoiceDoorCommands.register(event.getDispatcher());
        LOGGER.info("[VoiceDoor] Commands terdaftar.");
    }
}
