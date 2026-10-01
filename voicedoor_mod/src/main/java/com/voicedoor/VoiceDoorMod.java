package com.voicedoor;

import com.voicedoor.block.ModBlocks;
import com.voicedoor.blockentity.ModBlockEntities;
import com.voicedoor.command.VoiceDoorCommands;
import com.voicedoor.config.VoiceDoorConfig;
import com.voicedoor.item.ModCreativeTabs;
import com.voicedoor.item.ModItems;
import com.voicedoor.network.ModNetwork;
import com.voicedoor.voice.VoiceChatPlugin;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * VoiceDoor - pintu Minecraft yang hanya terbuka setelah verifikasi suara pemiliknya.
 *
 * <p>Alur pemakaian:
 * <ol>
 *   <li>Jalankan Python API: {@code python voice_api_server.py} (mencetak token).</li>
 *   <li>Di dalam game, OP menjalankan {@code /voicedoor settoken <token>}.</li>
 *   <li>Pasang blok VoiceDoor.</li>
 *   <li>Arahkan pandangan ke pintu, jalankan {@code /voicedoor register} beberapa kali
 *       sambil berbicara. Pendaftar pertama menjadi pemilik pintu.</li>
 *   <li>Klik pintu, ucapkan apa pun (atau frasa yang diminta), pintu terbuka kalau
 *       suaranya cocok.</li>
 * </ol>
 *
 * <p>Tidak perlu melatih model dan tidak perlu anggota kedua: server memakai speaker
 * verification, bukan klasifikasi antar anggota.
 */
@Mod(VoiceDoorMod.MOD_ID)
public class VoiceDoorMod {

    public static final String MOD_ID = "voicedoor";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    /** Apakah Simple Voice Chat terpasang. Tanpa ini pintu tidak bisa merekam suara. */
    public static boolean VOICE_CHAT_AVAILABLE = false;

    public VoiceDoorMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        VoiceDoorConfig.register();
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        VOICE_CHAT_AVAILABLE = ModList.get().isLoaded("voicechat");
        if (VOICE_CHAT_AVAILABLE) {
            LOGGER.info("[VoiceDoor] Simple Voice Chat terdeteksi - integrasi suara aktif.");
        } else {
            LOGGER.warn("[VoiceDoor] Simple Voice Chat TIDAK ditemukan. Pintu tidak akan bisa "
                    + "merekam suara. Pasang mod 'voicechat' agar mod ini berfungsi.");
        }

        ModNetwork.register();
        LOGGER.info("[VoiceDoor] Paket jaringan terdaftar.");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[VoiceDoor] API server yang dipakai: {}", VoiceDoorConfig.apiUrl());
        if (!VoiceDoorConfig.hasToken()) {
            LOGGER.warn("[VoiceDoor] Token API belum diatur. Jalankan '/voicedoor settoken <token>' "
                    + "dengan token yang dicetak voice_api_server.py, atau jalankan server API "
                    + "dengan --no-auth untuk pengujian lokal.");
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        VoiceChatPlugin.clearAll();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        VoiceDoorCommands.register(event.getDispatcher());
    }

    /**
     * Beri {@link VoiceChatPlugin} satu tick per tick server.
     *
     * <p>Inilah yang menutup sesi rekaman yang kedaluwarsa. Simple Voice Chat hanya
     * memanggil mod ini ketika ada paket mikrofon, jadi pemain yang tidak berbicara tidak
     * akan pernah menyelesaikan sesinya tanpa tick dari luar.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            VoiceChatPlugin.tick();
        }
    }
}
