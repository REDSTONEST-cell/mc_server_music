package io.github.rvlion.dgmusic;

import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;

/**
 * 配置文件的不可变快照，/dgmusic reload 时整体替换。
 */
public record Settings(
        String prefix,
        String qqCookie,
        List<String> qualities,
        int timeoutSeconds,
        int searchResults,
        int searchCooldownSeconds,
        String httpBind,
        int httpPort,
        String publicUrl,
        String ffmpeg,
        int oggQuality,
        int maxDurationSeconds,
        long maxDownloadBytes,
        int maxCachedSongs,
        int packFormat,
        String packPrompt,
        int queueMaxSize,
        int queueMaxPerPlayer,
        Sound.Source soundSource,
        float volume,
        int endGraceSeconds,
        boolean playOnJoin
) {

    public static Settings load(FileConfiguration c, String serverIp) {
        int port = c.getInt("http.port", 8765);
        String publicUrl = c.getString("http.public-url", "").trim();
        if (publicUrl.isEmpty()) {
            String host = serverIp == null || serverIp.isBlank() ? "127.0.0.1" : serverIp;
            publicUrl = "http://" + host + ":" + port;
        }
        while (publicUrl.endsWith("/")) {
            publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
        }

        List<String> qualities = c.getStringList("qq.qualities").stream()
                .map(q -> q.trim().toUpperCase(Locale.ROOT))
                .filter(q -> !q.isEmpty())
                .toList();
        if (qualities.isEmpty()) {
            qualities = List.of("M500", "C400", "M800");
        }

        Sound.Source source;
        try {
            source = Sound.Source.valueOf(c.getString("playback.sound-source", "RECORD").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source = Sound.Source.RECORD;
        }

        return new Settings(
                c.getString("prefix", "<gold>[点歌]</gold> "),
                c.getString("qq.cookie", "").trim(),
                qualities,
                Math.max(1, c.getInt("qq.timeout", 15)),
                Math.max(1, Math.min(30, c.getInt("search.results", 10))),
                Math.max(0, c.getInt("search.cooldown", 3)),
                c.getString("http.bind", "0.0.0.0"),
                port,
                publicUrl,
                c.getString("audio.ffmpeg", "ffmpeg"),
                Math.max(0, Math.min(10, c.getInt("audio.ogg-quality", 3))),
                Math.max(10, c.getInt("audio.max-duration", 600)),
                Math.max(1L, c.getLong("audio.max-download-mb", 50)) * 1024L * 1024L,
                Math.max(1, c.getInt("audio.max-cached-songs", 30)),
                c.getInt("resource-pack.pack-format", 75),
                c.getString("resource-pack.prompt", ""),
                Math.max(1, c.getInt("queue.max-size", 20)),
                Math.max(1, c.getInt("queue.max-per-player", 2)),
                source,
                (float) c.getDouble("playback.volume", 1.0),
                Math.max(0, c.getInt("playback.end-grace", 8)),
                c.getBoolean("playback.play-on-join", true)
        );
    }
}
