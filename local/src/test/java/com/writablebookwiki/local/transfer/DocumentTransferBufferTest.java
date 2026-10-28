package com.writablebookwiki.local.transfer;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DocumentTransferBufferTest {
    private final AtomicLong clock = new AtomicLong(100);

    private DocumentTransferBuffer buffer() {
        return new DocumentTransferBuffer(5, 2, 3, 10, 20, clock::get);
    }

    @Test void rejectsInvalidHeadersWithoutAllocatingTheirDeclaredSize() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentTransferBuffer(0, 1, 3, 10, 20, clock::get));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTransferBuffer(-1, 1, 3, 10, 20, clock::get));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTransferBuffer(Integer.MAX_VALUE, 1, 3, 10, 20, clock::get));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTransferBuffer(5, 1, 3, 10, 20, clock::get));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTransferBuffer(5, 2, 0, 10, 20, clock::get));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTransferBuffer(5, 2, 3, 10, 0, clock::get));
    }

    @Test void completesOnlyExactOrderedChunksAndIsolatesCallerArrays() {
        var transfer = buffer();
        byte[] first = {1, 2, 3};
        assertTrue(transfer.accept(0, first));
        first[0] = 9;
        assertFalse(transfer.isComplete());
        assertThrows(IllegalStateException.class, transfer::bytes);
        assertTrue(transfer.accept(1, new byte[] {4, 5}));
        assertTrue(transfer.isComplete());
        byte[] snapshot = transfer.bytes();
        snapshot[0] = 8;
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, transfer.bytes());
        assertFalse(transfer.accept(2, new byte[0]));
    }

    @Test void rejectedChunksDoNotAdvanceOrRefreshTimeout() {
        var transfer = buffer();
        clock.set(119);
        assertFalse(transfer.accept(1, new byte[] {4, 5}));
        assertFalse(transfer.accept(-1, new byte[] {1, 2, 3}));
        assertFalse(transfer.accept(0, null));
        assertFalse(transfer.accept(0, new byte[] {1, 2}));
        assertFalse(transfer.accept(0, new byte[] {1, 2, 3, 4}));
        assertTrue(transfer.isExpired(121));
        assertTrue(transfer.accept(0, new byte[] {1, 2, 3}));
        clock.set(139);
        assertFalse(transfer.accept(0, new byte[] {1, 2, 3}));
        assertTrue(transfer.isExpired(140));
        assertFalse(transfer.accept(1, new byte[] {4, 5, 6}));
        assertTrue(transfer.accept(1, new byte[] {4, 5}));
    }

    @Test void timeoutUsesElapsedMonotonicTimeAndPreservesBoundary() {
        var transfer = buffer();
        assertFalse(transfer.isExpired(120));
        assertTrue(transfer.isExpired(121));
        clock.set(Long.MAX_VALUE - 10);
        transfer = buffer();
        assertFalse(transfer.isExpired(Long.MIN_VALUE + 9));
        assertTrue(transfer.isExpired(Long.MIN_VALUE + 10));
    }
}
