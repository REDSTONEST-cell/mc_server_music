package io.github.rvlion.dgmusic.audio;

import io.github.rvlion.dgmusic.Settings;
import io.github.rvlion.dgmusic.qq.QQMusicClient;
import io.github.rvlion.dgmusic.qq.QQMusicException;
import io.github.rvlion.dgmusic.qq.Song;
import net.kyori.adventure.key.Key;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 下载歌曲 → ffmpeg 转码为 OGG Vorbis → 打包成资源包。
 */
public final class TrackPreparer {

    public static final String NAMESPACE = "dgmusic";

    private final QQMusicClient client;
    private final ExecutorService executor;
    private final Path packDir;
    private final Path tmpDir;
    private final Logger logger;
    private final Supplier<Settings> settings;
    private final Supplier<Set<String>> activeIds;
    private final Map<String, CompletableFuture<PreparedTrack>> inFlight = new ConcurrentHashMap<>();

    public TrackPreparer(QQMusicClient client, ExecutorService executor, Path dataDir, Logger logger,
                         Supplier<Settings> settings, Supplier<Set<String>> activeIds) throws IOException {
        this.client = client;
        this.executor = executor;
        this.packDir = Files.createDirectories(dataDir.resolve("packs"));
        this.tmpDir = Files.createDirectories(dataDir.resolve("tmp"));
        this.logger = logger;
        this.settings = settings;
        this.activeIds = activeIds;
        cleanTmp();
    }

    public Path packDir() {
        return packDir;
    }

    public static String idOf(Song song) {
        return sha1Hex(song.mid().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    public CompletableFuture<PreparedTrack> prepare(Song song) {
        String id = idOf(song);
        CompletableFuture<PreparedTrack> created = new CompletableFuture<>();
        CompletableFuture<PreparedTrack> existing = inFlight.putIfAbsent(id, created);
        if (existing != null) {
            return existing;
        }
        executor.execute(() -> {
            try {
                created.complete(doPrepare(song, id));
            } catch (Throwable e) {
                created.completeExceptionally(e);
            } finally {
                inFlight.remove(id, created);
            }
        });
        return created;
    }

    private PreparedTrack doPrepare(Song song, String id) throws IOException, QQMusicException {
        Settings s = settings.get();
        Path zip = packDir.resolve(id + ".zip");

        if (!Files.isRegularFile(zip)) {
            QQMusicClient.PlayUrl playUrl = client.resolvePlayUrl(song, s.qualities(), s.qqCookie(), s.timeoutSeconds());
            Path source = tmpDir.resolve(id + playUrl.extension());
            Path ogg = tmpDir.resolve(id + ".ogg");
            Path zipTmp = tmpDir.resolve(id + ".zip.part");
            try {
                download(playUrl.url(), source, s);
                transcode(source, ogg, s);
                writePack(ogg, zipTmp, id, song, s.packFormat());
                Files.move(zipTmp, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(source);
                Files.deleteIfExists(ogg);
                Files.deleteIfExists(zipTmp);
            }
            pruneCache(s.maxCachedSongs(), id);
        } else {
            // 刷新修改时间，避免被当作最旧的缓存清理掉
            Files.setLastModifiedTime(zip, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        }

        String sha1 = sha1Hex(zip);
        UUID packId = UUID.nameUUIDFromBytes((NAMESPACE + ":" + id).getBytes(StandardCharsets.UTF_8));
        String url = s.publicUrl() + "/packs/" + id + ".zip";
        return new PreparedTrack(song, id, packId, url, sha1, Key.key(NAMESPACE, "music." + id));
    }

    private void download(String url, Path target, Settings s) throws IOException, QQMusicException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(60, s.timeoutSeconds() * 4L)))
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", QQMusicClient.USER_AGENT)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> resp = client.http().send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) {
                    throw new QQMusicException("下载歌曲失败（HTTP " + resp.statusCode() + "）");
                }
                long declared = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (declared > s.maxDownloadBytes()) {
                    throw new QQMusicException("歌曲文件过大");
                }
                try (OutputStream out = Files.newOutputStream(target)) {
                    byte[] buf = new byte[64 * 1024];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > s.maxDownloadBytes()) {
                            throw new QQMusicException("歌曲文件过大");
                        }
                        out.write(buf, 0, n);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        } catch (IOException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new QQMusicException("下载歌曲失败：" + reason, e);
        }
    }

    private void transcode(Path source, Path ogg, Settings s) throws IOException, QQMusicException {
        Path log = tmpDir.resolve(ogg.getFileName() + ".log");
        ProcessBuilder pb = new ProcessBuilder(List.of(
                s.ffmpeg(), "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
                "-i", source.toString(),
                "-vn", "-map_metadata", "-1",
                // 双声道声音在客户端不会随距离衰减，全服都能听清
                "-ac", "2", "-ar", "44100",
                "-c:a", "libvorbis", "-q:a", String.valueOf(s.oggQuality()),
                "-t", String.valueOf(s.maxDurationSeconds()),
                ogg.toString()
        )).redirectErrorStream(true).redirectOutput(log.toFile());
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new QQMusicException("无法启动 ffmpeg，请确认服务器已安装 ffmpeg 并正确配置 audio.ffmpeg", e);
        }
        try {
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new QQMusicException("ffmpeg 转码超时");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(ogg) || Files.size(ogg) == 0) {
                String out = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8).trim() : "";
                logger.warning("ffmpeg 转码失败 (exit=" + process.exitValue() + "): " + out);
                throw new QQMusicException("音频转码失败");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("转码被中断", e);
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static void writePack(Path ogg, Path zip, String id, Song song, int packFormat) throws IOException {
        String description = (song.displayName()).replace("\\", "\\\\").replace("\"", "\\\"");
        String mcmeta = """
                {
                  "pack": {
                    "description": "DgMusic: %s",
                    "pack_format": %d,
                    "min_format": %d,
                    "max_format": %d
                  }
                }
                """.formatted(description, packFormat, packFormat, packFormat);
        String soundsJson = """
                {
                  "music.%s": {
                    "sounds": [
                      { "name": "%s:music/%s", "stream": true }
                    ]
                  }
                }
                """.formatted(id, NAMESPACE, id);

        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            putEntry(out, "pack.mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));
            putEntry(out, "assets/" + NAMESPACE + "/sounds.json", soundsJson.getBytes(StandardCharsets.UTF_8));
            out.putNextEntry(new ZipEntry("assets/" + NAMESPACE + "/sounds/music/" + id + ".ogg"));
            Files.copy(ogg, out);
            out.closeEntry();
        }
    }

    private static void putEntry(ZipOutputStream out, String name, byte[] data) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
    }

    private void pruneCache(int max, String keepId) {
        Set<String> active = activeIds.get();
        try (Stream<Path> files = Files.list(packDir)) {
            List<Path> zips = files.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong(TrackPreparer::lastModified).reversed())
                    .toList();
            for (int i = max; i < zips.size(); i++) {
                Path p = zips.get(i);
                String id = p.getFileName().toString().replace(".zip", "");
                if (id.equals(keepId) || active.contains(id) || inFlight.containsKey(id)) continue;
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "清理歌曲缓存失败", e);
        }
    }

    private void cleanTmp() {
        try (Stream<Path> files = Files.list(tmpDir)) {
            for (Path p : files.toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "清理临时目录失败", e);
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String sha1Hex(Path file) throws IOException {
        MessageDigest md = sha1();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String sha1Hex(byte[] data) {
        return HexFormat.of().formatHex(sha1().digest(data));
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
