package com.voicedoor.garage;

import com.voicedoor.blockentity.VoiceDoorBlockEntity;
import com.voicedoor.config.VoiceDoorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

/**
 * Abstraksi target pintu untuk command VoiceDoor, mendukung baik blok asli VoiceDoor
 * maupun Macaw's Garage Door (garage_silver_door dsb).
 */
public interface DoorTarget {

    BlockPos getPos();

    boolean hasOwner();

    @Nullable
    UUID getOwnerUUID();

    String getOwnerName();

    void setOwner(UUID uuid, String name);

    void clearOwner();

    Collection<UUID> authorizedList();

    void addAuthorizedPlayer(UUID uuid);

    boolean removeAuthorizedPlayer(UUID uuid);

    boolean isOpen();

    int getRemainingOpenTicks();

    String getApiUrl();

    boolean hasApiUrlOverride();

    boolean isGarageDoor();

    default Collection<String> getVoiceMembers() {
        return Collections.emptyList();
    }

    default boolean addVoice(String member) {
        return false;
    }

    default boolean removeVoice(String member) {
        return false;
    }

    default String getVoiceQueryString() {
        return getOwnerName();
    }

    @Nullable
    static DoorTarget findLookedAt(ServerPlayer player) {
        HitResult hit = player.pick(6.0D, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        Level level = player.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }

        // 1. Cek VoiceDoorBlockEntity biasa
        if (level.getBlockEntity(pos) instanceof VoiceDoorBlockEntity door) {
            return new BlockEntityTarget(door);
        }
        if (level.getBlockEntity(pos.below()) instanceof VoiceDoorBlockEntity door) {
            return new BlockEntityTarget(door);
        }

        // 2. Cek Macaw's Garage Door
        BlockState state = level.getBlockState(pos);
        if (GarageDoorHelper.isGarageDoor(state.getBlock())) {
            BlockPos topPos = GarageDoorHelper.findTopPos(level, pos);
            // Rangkaian pintu bergandengan = satu pintu: arahkan ke kolom yang memegang kunci
            GarageDoorData.Entry lock = GarageDoorHelper.findLock(serverLevel, topPos);
            return new GarageTarget(serverLevel, lock != null ? lock.pos : topPos);
        }

        return null;
    }

    class BlockEntityTarget implements DoorTarget {
        private final VoiceDoorBlockEntity be;

        public BlockEntityTarget(VoiceDoorBlockEntity be) {
            this.be = be;
        }

        @Override
        public BlockPos getPos() {
            return be.getBlockPos();
        }

        @Override
        public boolean hasOwner() {
            return be.hasOwner();
        }

        @Override
        public UUID getOwnerUUID() {
            return be.getOwnerUUID();
        }

        @Override
        public String getOwnerName() {
            return be.getOwnerName();
        }

        @Override
        public void setOwner(UUID uuid, String name) {
            be.setOwner(uuid, name);
        }

        @Override
        public void clearOwner() {
            be.clearOwner();
        }

        @Override
        public Collection<UUID> authorizedList() {
            return be.authorizedList();
        }

        @Override
        public void addAuthorizedPlayer(UUID uuid) {
            be.addAuthorizedPlayer(uuid);
        }

        @Override
        public boolean removeAuthorizedPlayer(UUID uuid) {
            return be.removeAuthorizedPlayer(uuid);
        }

        @Override
        public boolean isOpen() {
            return be.isOpen();
        }

        @Override
        public int getRemainingOpenTicks() {
            return be.getRemainingOpenTicks();
        }

        @Override
        public String getApiUrl() {
            return be.getApiUrl();
        }

        @Override
        public boolean hasApiUrlOverride() {
            return be.hasApiUrlOverride();
        }

        @Override
        public boolean isGarageDoor() {
            return false;
        }
    }

    class GarageTarget implements DoorTarget {
        private final ServerLevel level;
        private final BlockPos topPos;

        public GarageTarget(ServerLevel level, BlockPos topPos) {
            this.level = level;
            this.topPos = topPos.immutable();
        }

        private GarageDoorData data() {
            return GarageDoorData.get(level);
        }

        private GarageDoorData.Entry entry() {
            return data().getEntry(topPos);
        }

        @Override
        public BlockPos getPos() {
            return topPos;
        }

        @Override
        public boolean hasOwner() {
            GarageDoorData.Entry e = entry();
            return e != null && e.hasOwner();
        }

        @Override
        public UUID getOwnerUUID() {
            GarageDoorData.Entry e = entry();
            return e != null ? e.ownerUUID : null;
        }

        @Override
        public String getOwnerName() {
            GarageDoorData.Entry e = entry();
            return e != null ? e.ownerName : "";
        }

        @Override
        public void setOwner(UUID uuid, String name) {
            data().getOrCreateEntry(topPos, uuid, name);
        }

        @Override
        public void clearOwner() {
            data().removeDoor(topPos);
        }

        @Override
        public Collection<UUID> authorizedList() {
            GarageDoorData.Entry e = entry();
            return e != null ? e.authorizedPlayers : Collections.emptyList();
        }

        @Override
        public void addAuthorizedPlayer(UUID uuid) {
            GarageDoorData.Entry e = entry();
            if (e != null) {
                if (e.authorizedPlayers.add(uuid)) {
                    data().setDirty();
                }
            }
        }

        @Override
        public boolean removeAuthorizedPlayer(UUID uuid) {
            GarageDoorData.Entry e = entry();
            if (e != null) {
                boolean removed = e.authorizedPlayers.remove(uuid);
                if (removed) data().setDirty();
                return removed;
            }
            return false;
        }

        @Override
        public boolean isOpen() {
            return GarageDoorHelper.isOpen(level, topPos);
        }

        @Override
        public int getRemainingOpenTicks() {
            GarageDoorData.Entry e = entry();
            return e != null ? e.openTicks : 0;
        }

        @Override
        public String getApiUrl() {
            return VoiceDoorConfig.apiUrl();
        }

        @Override
        public boolean hasApiUrlOverride() {
            return false;
        }

        @Override
        public boolean isGarageDoor() {
            return true;
        }

        @Override
        public Collection<String> getVoiceMembers() {
            GarageDoorData.Entry e = entry();
            return e != null ? e.getVoiceMembers() : Collections.emptyList();
        }

        @Override
        public boolean addVoice(String member) {
            GarageDoorData.Entry e = entry();
            if (e != null) {
                boolean added = e.addVoice(member);
                if (added) data().setDirty();
                return added;
            }
            return false;
        }

        @Override
        public boolean removeVoice(String member) {
            GarageDoorData.Entry e = entry();
            if (e != null) {
                boolean removed = e.removeVoice(member);
                if (removed) data().setDirty();
                return removed;
            }
            return false;
        }

        @Override
        public String getVoiceQueryString() {
            GarageDoorData.Entry e = entry();
            return e != null ? e.getVoiceQueryString() : getOwnerName();
        }
    }
}
