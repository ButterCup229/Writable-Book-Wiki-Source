package com.writablebookwiki.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkedAssetReceiverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void streamsVerifiedChunksIntoATemporaryFile() throws Exception {
        byte[] content = "0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ChunkedAssetReceiver receiver = ChunkedAssetReceiver.create(temporaryDirectory, "image-", hash(content),
                content.length, 3, 4);

        assertTrue(receiver.append(0, slice(content, 0, 4)));
        assertTrue(receiver.append(1, slice(content, 4, 8)));
        assertTrue(receiver.append(2, slice(content, 8, 10)));

        Path completed = receiver.finish().orElseThrow();
        assertArrayEquals(content, Files.readAllBytes(completed));
        Files.deleteIfExists(completed);
    }

    @Test
    void rejectsWrongOrderAndCleansUpHashMismatch() throws Exception {
        byte[] content = "abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ChunkedAssetReceiver receiver = ChunkedAssetReceiver.create(temporaryDirectory, "image-", hash(content),
                content.length, 2, 3);

        assertFalse(receiver.append(1, slice(content, 3, 6)));
        assertTrue(receiver.append(0, "xxx".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(receiver.append(1, slice(content, 3, 6)));
        assertTrue(receiver.finish().isEmpty());
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(0L, files.count());
        }
    }

    private static byte[] slice(byte[] source, int start, int end) {
        return java.util.Arrays.copyOfRange(source, start, end);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void assertEquals(long expected, long actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
