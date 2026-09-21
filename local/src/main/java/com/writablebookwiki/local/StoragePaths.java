package com.writablebookwiki.local;

import com.writablebookwiki.core.ModIdentity;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves storage without moving, copying, deleting, or silently replacing existing user data. */
public final class StoragePaths {
    private StoragePaths() {
    }

    /** New installs use the new name; an existing legacy-only installation keeps its data in place. */
    public static Path dataRoot(Path parent) {
        Path current = parent.resolve(ModIdentity.MOD_ID);
        if (Files.exists(current)) {
            requireDirectory(current);
            return current;
        }
        Path legacy = parent.resolve(ModIdentity.LEGACY_MOD_ID);
        if (Files.exists(legacy)) {
            requireDirectory(legacy);
            return legacy;
        }
        return current;
    }

    /** Preserve an existing transfer configuration, including its user's custom limits. */
    public static String serverConfigName(Path configDirectory) {
        String current = ModIdentity.MOD_ID + "-server.toml";
        String legacy = ModIdentity.LEGACY_MOD_ID + "-server.toml";
        return !Files.exists(configDirectory.resolve(current))
                && Files.isRegularFile(configDirectory.resolve(legacy)) ? legacy : current;
    }

    private static void requireDirectory(Path path) {
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("Mod data path is not a directory: " + path);
        }
    }
}
