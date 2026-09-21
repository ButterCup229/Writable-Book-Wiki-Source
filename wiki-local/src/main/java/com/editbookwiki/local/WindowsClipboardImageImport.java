package com.editbookwiki.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Windows clipboard bridge for Minecraft's headless Java runtime; never modifies the clipboard. */
public final class WindowsClipboardImageImport {
    private WindowsClipboardImageImport() { }

    private static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            Add-Type -AssemblyName System.Windows.Forms
            Add-Type -AssemblyName System.Drawing
            if ([System.Windows.Forms.Clipboard]::ContainsImage()) {
                $picture = [System.Windows.Forms.Clipboard]::GetImage()
                if ($null -eq $picture) { throw 'Clipboard image unavailable' }
                try {
                    if ($picture.Width -gt 16384 -or $picture.Height -gt 16384 -or
                        ([long]$picture.Width * $picture.Height) -gt 67108864) { throw 'Image dimensions too large' }
                    $picture.Save($env:EDITBOOKWIKI_CLIPBOARD_FILE, [System.Drawing.Imaging.ImageFormat]::Png)
                } finally { $picture.Dispose() }
                exit 0
            }
            if ([System.Windows.Forms.Clipboard]::ContainsFileDropList()) {
                $files = [System.Windows.Forms.Clipboard]::GetFileDropList()
                if ($files.Count -eq 1 -and [System.IO.Path]::GetExtension($files[0]) -match '^\\.(png|jpg|jpeg)$') {
                    [System.IO.File]::Copy($files[0], $env:EDITBOOKWIKI_CLIPBOARD_FILE, $true)
                    exit 0
                }
            }
            exit 2
            """;

    public static ImportedImageAsset importImage(Path directory, ClipboardImageImport.Importer importer) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "clipboard-win-", ".png");
        Process process = null;
        try {
            String windows = System.getenv("SystemRoot");
            if (windows == null) throw new IOException("Windows directory unavailable");
            Path shell = Path.of(windows, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder builder = new ProcessBuilder(shell.toString(), "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-STA", "-EncodedCommand", encoded);
            builder.environment().put("EDITBOOKWIKI_CLIPBOARD_FILE", temporary.toAbsolutePath().toString());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IOException("读取剪贴板超时，请重试");
            if (process.exitValue() == 2) return null;
            if (process.exitValue() != 0) throw new IOException("无法读取剪贴板图片，请重新复制");
            return importer.importImage(temporary);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("图片粘贴已中断", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            Files.deleteIfExists(temporary);
        }
    }
}
