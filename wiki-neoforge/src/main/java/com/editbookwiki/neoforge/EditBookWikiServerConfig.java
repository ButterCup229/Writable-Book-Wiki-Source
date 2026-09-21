package com.editbookwiki.neoforge;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-owned settings for book-body and image delivery.
 *
 * <p>These are deliberately transport settings, rather than moderation or
 * anti-spam rules. A value of {@code 0} means no gameplay limit. The network
 * protocol still keeps its small packet and bounded-buffer checks so malformed
 * data cannot force arbitrary allocations.</p>
 */
public final class EditBookWikiServerConfig {
    private static final int MAX_SUPPORTED_DISTRIBUTION_BYTES = BookNetwork.MAX_IMAGE_BYTES;
    private static final int MAX_SUPPORTED_BYTES_PER_SECOND = 16 * 1024 * 1024;

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue MAX_BYTES_PER_DISTRIBUTION;
    private static final ModConfigSpec.IntValue DOWNLOAD_BYTES_PER_SECOND;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("编辑书 Wiki 的世界书籍传输设置。0 表示不设玩法限制；协议仍保留技术安全边界。")
                .push("sync");
        MAX_BYTES_PER_DISTRIBUTION = builder
                .comment("单次正文或图片分发的最大字节数。0 = 不限（仍受协议技术上限约束）。")
                .defineInRange("max_bytes_per_distribution", 0, 0, MAX_SUPPORTED_DISTRIBUTION_BYTES);
        DOWNLOAD_BYTES_PER_SECOND = builder
                .comment("服务器向每位玩家发送书籍正文和图片的最高速度（字节/秒）。0 = 不限。")
                .defineInRange("download_bytes_per_second", 0, 0, MAX_SUPPORTED_BYTES_PER_SECOND);
        builder.pop();
        SPEC = builder.build();
    }

    private EditBookWikiServerConfig() {
    }

    /** Returns true when the configured gameplay limit admits this transfer. */
    public static boolean allowsDistribution(int byteCount) {
        int configuredLimit = MAX_BYTES_PER_DISTRIBUTION.get();
        return configuredLimit == 0 || byteCount <= configuredLimit;
    }

    /**
     * A limited transfer must still make forward progress before the receive
     * timeout. One KiB/s yields a 24 KiB packet roughly every 24 seconds.
     */
    public static int downloadBytesPerSecond() {
        int configuredLimit = DOWNLOAD_BYTES_PER_SECOND.get();
        return configuredLimit == 0 ? 0 : Math.max(1024, configuredLimit);
    }
}
