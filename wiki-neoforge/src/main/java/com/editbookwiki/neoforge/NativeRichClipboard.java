package com.editbookwiki.neoforge;

import com.editbookwiki.core.document.TextFragment;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.WindowsLibrary;

/** Keeps rich selections inside this game process; the OS clipboard still contains ordinary text. */
final class NativeRichClipboard {
    private static TextFragment fragment;
    private static long copiedSequence;

    private NativeRichClipboard() { }

    /** Clipboard listeners can briefly hold a write; all retry waits combined are bounded to 150 ms. */
    static boolean copy(Consumer<String> writer, Supplier<String> reader, String text, TextFragment value) {
        fragment = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            long before = sequence();
            writer.accept(text);
            long after = sequence();
            if ((before == 0 || after != before) && text.equals(readText(reader))) {
                remember(value, text);
                return true;
            }
            if (attempt < 3) briefRetry();
        }
        return false;
    }

    static String readText(Supplier<String> reader) {
        for (int attempt = 0; attempt < 4; attempt++) {
            String value = reader.get();
            if (value != null && !value.isEmpty()) return value;
            if (attempt < 3) briefRetry();
        }
        return "";
    }

    private static void briefRetry() {
        try { Thread.sleep(10); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    static void remember(TextFragment value, String actualClipboardText) {
        long sequence = sequence();
        fragment = value != null && sequence != 0 && value.plainText().equals(actualClipboardText) ? value : null;
        copiedSequence = sequence;
    }

    static TextFragment matching(String text) {
        if (fragment == null) return null;
        if (sequence() != copiedSequence || !fragment.plainText().equals(text)) {
            fragment = null;
            return null;
        }
        return fragment;
    }

    private static long sequence() {
        if (Platform.get() != Platform.WINDOWS) return 0; // Safe plain-text fallback, never guess by text equality.
        try {
            long address = Windows.SEQUENCE;
            return address == 0 ? 0 : Integer.toUnsignedLong(JNI.callI(address));
        } catch (RuntimeException | LinkageError unavailable) {
            return 0;
        }
    }

    private static final class Windows {
        // Retain the library for the function pointer's lifetime; no additional native dependency.
        static final WindowsLibrary USER32 = new WindowsLibrary("user32.dll");
        static final long SEQUENCE = USER32.getFunctionAddress("GetClipboardSequenceNumber");
    }
}
