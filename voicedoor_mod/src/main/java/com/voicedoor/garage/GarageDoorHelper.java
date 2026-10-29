package com.voicedoor.garage;

import com.mcwdoors.kikoz.init.SoundsInit;
import com.mcwdoors.kikoz.objects.GarageDoor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Utilitas untuk berinteraksi dengan Garage Door dari mod Macaw's Doors.
 *
 * <p>Macaw's membuka/menutup semua garage door yang bergandengan secara rekursif
 * (GarageDoor#toggleDoor). Rangkaian itu diperlakukan sebagai satu pintu: bila satu kolom
 * dikunci VoiceDoor, seluruh rangkaian ikut terkunci (lihat {@link #findLock}).
 */
public final class GarageDoorHelper {

    /** Batas jumlah kolom dalam satu rangkaian, mencegah flood-fill tak terbatas. */
    private static final int MAX_LINKED_DOORS = 64;

    private GarageDoorHelper() {
    }

    /**
     * Memeriksa apakah blok adalah Garage Door Macaw's (misal: garage_silver_door).
     */
    public static boolean isGarageDoor(Block block) {
        if (block instanceof GarageDoor) {
            return true;
        }
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        if (id != null && "mcwdoors".equals(id.getNamespace()) && id.getPath().startsWith("garage_")) {
            return true;
        }
        return false;
    }

    /**
     * Mencari posisi blok bagian atas (TOP) dari pintu garasi.
     * Macaw's Doors menyimpan state OPEN dan orientasi utama pada blok paling atas.
     */
    public static BlockPos findTopPos(Level level, BlockPos pos) {
        BlockPos current = pos;
        Block targetBlock = level.getBlockState(current).getBlock();
        while (level.getBlockState(current.above()).getBlock() == targetBlock) {
            current = current.above();
        }
        return current;
    }

    /**
     * Cek apakah pintu garasi sedang terbuka.
     */
    public static boolean isOpen(Level level, BlockPos pos) {
        BlockPos topPos = findTopPos(level, pos);
        BlockState state = level.getBlockState(topPos);
        if (state.hasProperty(GarageDoor.OPEN)) {
            return state.getValue(GarageDoor.OPEN);
        }
        return false;
    }

    /**
     * Membuka atau menutup pintu garasi beserta semua pintu yang bergandengan dengannya.
     *
     * @return posisi TOP semua kolom dalam rangkaian (termasuk yang sudah dalam kondisi tujuan)
     */
    public static Set<BlockPos> setDoorOpen(ServerLevel level, BlockPos pos, boolean open) {
        BlockPos topPos = findTopPos(level, pos);
        if (!(level.getBlockState(topPos).getBlock() instanceof GarageDoor)) {
            return Collections.emptySet();
        }

        Set<BlockPos> linked = findLinkedDoors(level, topPos);
        boolean changed = false;
        for (BlockPos column : linked) {
            changed |= setColumnOpen(level, column, open);
        }
        if (changed) {
            playSound(level, topPos);
        }
        return linked;
    }

    /**
     * Mencari kunci VoiceDoor milik rangkaian pintu: satu rangkaian garage door yang
     * bergandengan dianggap satu pintu, jadi bila satu kolom terkunci semuanya terkunci.
     * Kolom yang diklik diprioritaskan bila kolom itu sendiri yang terkunci.
     */
    @Nullable
    public static GarageDoorData.Entry findLock(ServerLevel level, BlockPos pos) {
        BlockPos topPos = findTopPos(level, pos);
        GarageDoorData data = GarageDoorData.get(level);
        GarageDoorData.Entry own = data.getEntry(topPos);
        if (own != null && own.hasOwner()) {
            return own;
        }
        if (!(level.getBlockState(topPos).getBlock() instanceof GarageDoor)) {
            return null;
        }
        for (BlockPos column : findLinkedDoors(level, topPos)) {
            GarageDoorData.Entry entry = data.getEntry(column);
            if (entry != null && entry.hasOwner()) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Flood-fill horizontal ke garage door sejenis yang bergandengan (sama seperti
     * rambatan GarageDoor#toggleDoor milik Macaw's).
     */
    public static Set<BlockPos> findLinkedDoors(ServerLevel level, BlockPos topPos) {
        Block block = level.getBlockState(topPos).getBlock();

        Set<BlockPos> linked = new LinkedHashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        linked.add(topPos.immutable());
        queue.add(topPos.immutable());

        while (!queue.isEmpty() && linked.size() < MAX_LINKED_DOORS) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = current.relative(dir);
                if (level.getBlockState(next).getBlock() != block) continue;
                next = findTopPos(level, next).immutable();
                if (linked.contains(next)) continue;
                linked.add(next);
                queue.add(next);
            }
        }
        return linked;
    }

    /**
     * Mengubah satu kolom pintu, meniru GarageDoor#toggleDoor tanpa bagian rekursif-nya:
     * saat terbuka bagian MIDDLE dihapus, saat tertutup ruang kosong di bawah TOP diisi MIDDLE.
     */
    private static boolean setColumnOpen(Level level, BlockPos topPos, boolean open) {
        BlockState state = level.getBlockState(topPos);
        if (!(state.getBlock() instanceof GarageDoor garageBlock) || !state.hasProperty(GarageDoor.OPEN)) {
            return false;
        }
        if (state.getValue(GarageDoor.OPEN) == open) {
            return false;
        }

        level.setBlock(topPos, state.setValue(GarageDoor.OPEN, open), 3);

        BlockPos below = topPos.below();
        if (open) {
            while (level.getBlockState(below).getBlock() == garageBlock) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), 3);
                below = below.below();
            }
        } else {
            BlockState middle = garageBlock.defaultBlockState()
                    .setValue(GarageDoor.FACING, state.getValue(GarageDoor.FACING))
                    .setValue(GarageDoor.PART, GarageDoor.GaragePart.MIDDLE)
                    .setValue(GarageDoor.OPEN, false);
            while (below.getY() >= level.getMinBuildHeight() && level.getBlockState(below).isAir()) {
                level.setBlock(below, middle, 3);
                below = below.below();
            }
        }
        return true;
    }

    private static void playSound(Level level, BlockPos pos) {
        try {
            SoundEvent sound = SoundsInit.GARAGE.get();
            if (sound != null) {
                level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.5f,
                        level.random.nextFloat() * 0.1f + 0.8f);
            }
        } catch (Throwable ignored) {
        }
    }
}
