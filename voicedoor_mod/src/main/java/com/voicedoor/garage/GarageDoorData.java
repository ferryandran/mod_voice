package com.voicedoor.garage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Penyimpanan persisten world untuk status kepemilikan Garage Door VoiceDoor.
 */
public class GarageDoorData extends SavedData {

    private static final String DATA_NAME = "voicedoor_garage_doors";

    private final Map<BlockPos, Entry> doors = new HashMap<>();

    public GarageDoorData() {
    }

    public static GarageDoorData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(GarageDoorData::load, GarageDoorData::new, DATA_NAME);
    }

    public static GarageDoorData load(CompoundTag tag) {
        GarageDoorData data = new GarageDoorData();
        ListTag list = tag.getList("Doors", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            BlockPos pos = new BlockPos(
                    entryTag.getInt("X"),
                    entryTag.getInt("Y"),
                    entryTag.getInt("Z")
            );
            UUID ownerUUID = entryTag.hasUUID("OwnerUUID") ? entryTag.getUUID("OwnerUUID") : null;
            String ownerName = entryTag.getString("OwnerName");

            Set<UUID> auth = new HashSet<>();
            if (entryTag.contains("Authorized", Tag.TAG_LIST)) {
                ListTag authList = entryTag.getList("Authorized", Tag.TAG_STRING);
                for (int j = 0; j < authList.size(); j++) {
                    try {
                        auth.add(UUID.fromString(authList.getString(j)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }

            Entry entry = new Entry(pos, ownerUUID, ownerName, auth);
            if (entryTag.contains("VoiceMembers", Tag.TAG_LIST)) {
                ListTag voiceList = entryTag.getList("VoiceMembers", Tag.TAG_STRING);
                for (int j = 0; j < voiceList.size(); j++) {
                    entry.addVoice(voiceList.getString(j));
                }
            } else if (ownerName != null && !ownerName.isEmpty()) {
                for (String part : ownerName.split(",")) {
                    entry.addVoice(part.trim());
                }
            }
            entry.openTicks = entryTag.getInt("OpenTicks");
            data.doors.put(pos, entry);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry entry : doors.values()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putInt("X", entry.pos.getX());
            entryTag.putInt("Y", entry.pos.getY());
            entryTag.putInt("Z", entry.pos.getZ());
            if (entry.ownerUUID != null) {
                entryTag.putUUID("OwnerUUID", entry.ownerUUID);
            }
            entryTag.putString("OwnerName", entry.ownerName == null ? "" : entry.ownerName);

            ListTag authList = new ListTag();
            for (UUID uuid : entry.authorizedPlayers) {
                authList.add(StringTag.valueOf(uuid.toString()));
            }
            entryTag.put("Authorized", authList);

            ListTag voiceList = new ListTag();
            for (String v : entry.voiceMembers) {
                voiceList.add(StringTag.valueOf(v));
            }
            entryTag.put("VoiceMembers", voiceList);

            entryTag.putInt("OpenTicks", entry.openTicks);

            list.add(entryTag);
        }
        tag.put("Doors", list);
        return tag;
    }

    @Nullable
    public Entry getEntry(BlockPos pos) {
        return doors.get(pos);
    }

    public Entry getOrCreateEntry(BlockPos topPos, UUID ownerUUID, String ownerName) {
        Entry entry = doors.computeIfAbsent(topPos.immutable(), p -> new Entry(p, ownerUUID, ownerName, new HashSet<>()));
        entry.ownerUUID = ownerUUID;
        entry.ownerName = ownerName;
        if (ownerName != null && !ownerName.isEmpty()) {
            for (String part : ownerName.split(",")) {
                entry.addVoice(part.trim());
            }
        }
        setDirty();
        return entry;
    }

    public boolean removeDoor(BlockPos topPos) {
        Entry removed = doors.remove(topPos);
        if (removed != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public Map<BlockPos, Entry> getAllDoors() {
        return Collections.unmodifiableMap(doors);
    }

    /**
     * Entry data untuk satu unit pintu garasi.
     */
    public static class Entry {
        public final BlockPos pos;
        public UUID ownerUUID;
        public String ownerName;
        public final Set<UUID> authorizedPlayers;
        public final Set<String> voiceMembers = new java.util.LinkedHashSet<>();
        public int openTicks = 0;
        public final Map<UUID, Integer> cooldowns = new HashMap<>();

        public Entry(BlockPos pos, UUID ownerUUID, String ownerName, Set<UUID> authorized) {
            this.pos = pos;
            this.ownerUUID = ownerUUID;
            this.ownerName = ownerName == null ? "" : ownerName;
            this.authorizedPlayers = authorized;
            if (this.ownerName != null && !this.ownerName.isEmpty()) {
                for (String part : this.ownerName.split(",")) {
                    addVoice(part.trim());
                }
            }
        }

        public Set<String> getVoiceMembers() {
            return Collections.unmodifiableSet(voiceMembers);
        }

        public boolean addVoice(String member) {
            if (member == null || member.trim().isEmpty()) return false;
            return voiceMembers.add(member.trim());
        }

        public boolean removeVoice(String member) {
            if (member == null) return false;
            boolean removed = voiceMembers.remove(member.trim());
            if (!removed) {
                removed = voiceMembers.removeIf(m -> m.equalsIgnoreCase(member.trim()));
            }
            return removed;
        }

        public String getVoiceQueryString() {
            if (voiceMembers.isEmpty()) {
                return ownerName != null ? ownerName : "";
            }
            return String.join(",", voiceMembers);
        }

        public boolean hasOwner() {
            return ownerUUID != null && ownerName != null && !ownerName.isEmpty();
        }

        public boolean isAuthorized(UUID uuid) {
            return (ownerUUID != null && ownerUUID.equals(uuid)) || authorizedPlayers.contains(uuid);
        }

        public void setCooldown(UUID uuid, int ticks) {
            cooldowns.put(uuid, ticks);
        }

        public int getCooldown(UUID uuid) {
            return cooldowns.getOrDefault(uuid, 0);
        }
    }
}
