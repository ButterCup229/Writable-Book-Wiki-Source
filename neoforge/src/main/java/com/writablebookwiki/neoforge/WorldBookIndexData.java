package com.writablebookwiki.neoforge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Small world-save index for externally stored editable-book documents.
 * It contains no document body or image bytes; those remain in the repository
 * directory owned by the current Minecraft world.
 */
final class WorldBookIndexData extends SavedData {
    private static final String DATA_NAME = WritableBookWikiMod.MOD_ID + "_book_index";
    private static final String RECORDS_KEY = "records";
    private static final String ID_KEY = "id";
    private static final String HASH_KEY = "hash";
    private static final String LEGACY_HASHES_KEY = "legacy_hashes";
    private static final SavedData.Factory<WorldBookIndexData> FACTORY = new SavedData.Factory<>(
            WorldBookIndexData::new, WorldBookIndexData::load, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, IndexRecord> records = new HashMap<>();

    static WorldBookIndexData get(ServerLevel level) {
        return get(level.getDataStorage());
    }

    static WorldBookIndexData get(net.minecraft.world.level.storage.DimensionDataStorage storage) {
        return storage.computeIfAbsent(FACTORY, DATA_NAME);
    }

    static WorldBookIndexData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldBookIndexData data = new WorldBookIndexData();
        ListTag records = tag.getList(RECORDS_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < records.size(); index++) {
            CompoundTag record = records.getCompound(index);
            if (!record.hasUUID(ID_KEY) || !record.contains(HASH_KEY, Tag.TAG_STRING)) {
                continue;
            }
            String hash = record.getString(HASH_KEY);
            if (hash.matches("[0-9a-f]{64}")) {
                Set<String> legacyHashes = new HashSet<>();
                ListTag rawLegacyHashes = record.getList(LEGACY_HASHES_KEY, Tag.TAG_STRING);
                for (int legacyIndex = 0; legacyIndex < rawLegacyHashes.size(); legacyIndex++) {
                    String legacyHash = rawLegacyHashes.getString(legacyIndex);
                    if (!hash.equals(legacyHash) && legacyHash.matches("[0-9a-f]{64}")) {
                        legacyHashes.add(legacyHash);
                    }
                }
                data.records.put(record.getUUID(ID_KEY), new IndexRecord(hash, Set.copyOf(legacyHashes)));
            }
        }
        return data;
    }

    Optional<String> contentHash(UUID bookId) {
        return Optional.ofNullable(records.get(bookId)).map(IndexRecord::currentHash);
    }

    void register(UUID bookId, String contentHash) {
        IndexRecord existing = records.get(bookId);
        IndexRecord replacement = existing != null && contentHash.equals(existing.currentHash())
                ? existing
                : new IndexRecord(contentHash, Set.of());
        if (!replacement.equals(existing)) {
            records.put(bookId, replacement);
            setDirty();
        }
    }

    /**
     * Records a schema-only migration without invalidating older physical
     * ItemStacks which still carry the prior, verified content hash.
     */
    void registerMigration(UUID bookId, String currentHash, String legacyHash) {
        Set<String> aliases = new HashSet<>();
        IndexRecord existing = records.get(bookId);
        if (existing != null && currentHash.equals(existing.currentHash())) {
            aliases.addAll(existing.legacyHashes());
        }
        if (!currentHash.equals(legacyHash)) {
            aliases.add(legacyHash);
        }
        IndexRecord replacement = new IndexRecord(currentHash, Set.copyOf(aliases));
        if (!replacement.equals(existing)) {
            records.put(bookId, replacement);
            setDirty();
        }
    }

    /** Only aliases registered by a verified migration may recover an old pointer. */
    boolean hasLegacyAlias(UUID bookId, String currentHash, String legacyHash) {
        IndexRecord record = records.get(bookId);
        return record != null && record.currentHash().equals(currentHash)
                && record.legacyHashes().contains(legacyHash);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag serializedRecords = new ListTag();
        this.records.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag record = new CompoundTag();
            record.putUUID(ID_KEY, entry.getKey());
            record.putString(HASH_KEY, entry.getValue().currentHash());
            ListTag legacyHashes = new ListTag();
            new TreeSet<>(entry.getValue().legacyHashes()).forEach(hash -> legacyHashes.add(StringTag.valueOf(hash)));
            if (!legacyHashes.isEmpty()) {
                record.put(LEGACY_HASHES_KEY, legacyHashes);
            }
            serializedRecords.add(record);
        });
        tag.put(RECORDS_KEY, serializedRecords);
        return tag;
    }

    private record IndexRecord(String currentHash, Set<String> legacyHashes) {
    }
}
