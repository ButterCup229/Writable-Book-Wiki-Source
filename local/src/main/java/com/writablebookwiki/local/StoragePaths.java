package com.writablebookwiki.local;

import com.writablebookwiki.core.ModIdentity;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves storage without moving, copying, deleting, or silently replacing existing user data. */
public final class StoragePaths {
    private StoragePaths() {
    }

    /** Data belongs exclusively to this mod's namespace; unrelated directories are never adopted. */
    public static Path dataRoot(Path parent) {
        Path current = parent.resolve(ModIdentity.MOD_ID);
        if (Files.exists(current)) {
            requireDirectory(current);
        }
        return current;
    }

    public static String serverConfigName() {
        return ModIdentity.MOD_ID + "-server.toml";
    }

    private static void requireDirectory(Path path) {
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("Mod data path is not a directory: " + path);
        }
    }
}
