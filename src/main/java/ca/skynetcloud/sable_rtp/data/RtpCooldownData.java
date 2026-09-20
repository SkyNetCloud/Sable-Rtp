package ca.skynetcloud.sable_rtp.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RtpCooldownData extends SavedData {

    private static final String SABLE_DATA_ID = "sable_rtp_cooldowns";

    private final Map<UUID, Long> lastUse = new HashMap<>();

    public static RtpCooldownData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RtpCooldownData::new, RtpCooldownData::load, null),
                SABLE_DATA_ID
        );
    }

    private  static RtpCooldownData load(CompoundTag compoundTag, HolderLookup.Provider registry) {
        RtpCooldownData data = new RtpCooldownData();
        ListTag entriesTag = compoundTag.getList("entries", ListTag.TAG_COMPOUND);
        for (int i = 0; i < entriesTag.size(); i++) {
            CompoundTag entryTag = entriesTag.getCompound(i);
            if (!entryTag.hasUUID("uuid")) continue;
            data.lastUse.put(entryTag.getUUID("uuid"), entryTag.getLong("time"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        lastUse.forEach((uuid, time) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("uuid", uuid);
            entry.putLong("time", time);
            entries.add(entry);
        });
        tag.put("entries", entries);
        return tag;
    }

    public void markUsed(UUID uuid, long epochMillis) {
        lastUse.put(uuid, epochMillis);
        setDirty();
    }

    public void clear(UUID uuid) {
        if (lastUse.remove(uuid) != null) setDirty();
    }

    public long remainingSeconds(UUID uuid, int cooldownSeconds) {
        Long last = lastUse.get(uuid);
        if (last == null) return 0L;

        long elapsedSeconds = (System.currentTimeMillis() - last) / 1000L;
        if (elapsedSeconds < 0) return 0L; // clock moved backwards

        return Math.max(0L, cooldownSeconds - elapsedSeconds);
    }

}
