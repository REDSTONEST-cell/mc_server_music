package io.github.rvlion.dgmusic.player;

import io.github.rvlion.dgmusic.DgMusicPlugin;
import io.github.rvlion.dgmusic.Settings;
import io.github.rvlion.dgmusic.audio.PreparedTrack;
import io.github.rvlion.dgmusic.audio.TrackPreparer;
import io.github.rvlion.dgmusic.qq.Song;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 全服播放队列。所有状态变更都在 this 上同步；对玩家的操作通过玩家自己的调度器执行，兼容 Folia。
 */
public final class MusicPlayer {

    public enum Result { STARTED, QUEUED, QUEUE_FULL, PLAYER_LIMIT, DUPLICATE }

    public record Outcome(Result result, int position) {
    }

    public record Entry(Song song, UUID requesterId, String requesterName,
                        CompletableFuture<PreparedTrack> prepared) {
    }

    public record Snapshot(Entry current, boolean playing, List<Entry> queue) {
    }

    private final DgMusicPlugin plugin;
    private final TrackPreparer preparer;

    private final Deque<Entry> queue = new ArrayDeque<>();
    private Entry current;
    private PreparedTrack currentTrack;
    private volatile long generation;
    private ScheduledTask endTask;

    /** 用 /dgdont 拒绝听歌的玩家，保存在 muted.txt，/dgon 后恢复 */
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Path mutedFile;
    /**
     * 每个玩家已加载的本插件资源包，按加载先后排列。
     * 客户端每次增删资源包都会重新加载资源（出现加载画面），所以这里只增不减，超出上限时随新歌一起卸载最旧的。
     */
    private final Map<UUID, Deque<UUID>> loadedPacks = new ConcurrentHashMap<>();

    public MusicPlayer(DgMusicPlugin plugin, TrackPreparer preparer, Path dataDir) {
        this.mutedFile = dataDir.resolve("muted.txt");
        loadMuted(plugin);
        this.plugin = plugin;
        this.preparer = preparer;
    }

    /**
     * 点歌。announce 会在切换播放状态之前调用，保证"点播了"的消息先于"正在播放"发出。
     */
    public synchronized Outcome enqueue(Song song, Player requester, boolean bypassLimit, Consumer<Outcome> announce) {
        Settings s = plugin.settings();
        Outcome rejected = null;
        if (isQueued(song)) {
            rejected = new Outcome(Result.DUPLICATE, 0);
        } else if (queue.size() >= s.queueMaxSize()) {
            rejected = new Outcome(Result.QUEUE_FULL, 0);
        } else if (!bypassLimit) {
            long mine = queue.stream().filter(e -> e.requesterId().equals(requester.getUniqueId())).count();
            if (current != null && current.requesterId().equals(requester.getUniqueId())) mine++;
            if (mine >= s.queueMaxPerPlayer()) {
                rejected = new Outcome(Result.PLAYER_LIMIT, 0);
            }
        }
        if (rejected != null) {
            announce.accept(rejected);
            return rejected;
        }

        Entry entry = new Entry(song, requester.getUniqueId(), requester.getName(), preparer.prepare(song));
        queue.addLast(entry);
        Outcome outcome = current == null ? new Outcome(Result.STARTED, 0) : new Outcome(Result.QUEUED, queue.size());
        announce.accept(outcome);
        if (current == null) {
            advance();
        }
        return outcome;
    }

    /** 切到下一首，announce 在切换前以被切掉的歌调用；没有正在播放的歌时返回 false。 */
    public synchronized boolean skip(Consumer<Song> announce) {
        if (current == null) return false;
        announce.accept(current.song());
        advance();
        return true;
    }

    /** 清空队列并停止播放，announce 在停止前调用；没有任何歌曲时返回 false。 */
    public synchronized boolean stopAll(Runnable announce) {
        if (current == null && queue.isEmpty()) return false;
        announce.run();
        queue.clear();
        advance();
        return true;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(current, currentTrack != null, List.copyOf(queue));
    }

    public synchronized Set<String> activeIds() {
        Set<String> ids = new HashSet<>();
        if (current != null) ids.add(TrackPreparer.idOf(current.song()));
        for (Entry e : queue) ids.add(TrackPreparer.idOf(e.song()));
        return ids;
    }

    public boolean isListening(Player player) {
        return !muted.contains(player.getUniqueId());
    }

    /**
     * 开启或关闭某个玩家的点歌收听，返回状态是否有变化。
     * 关闭时只停止声音，不卸载已加载的资源包，避免客户端出现加载画面。
     */
    public boolean setListening(Player player, boolean listening) {
        UUID uuid = player.getUniqueId();
        boolean changed = listening ? muted.remove(uuid) : muted.add(uuid);
        if (!changed) return false;
        saveMuted();

        PreparedTrack track;
        long g;
        synchronized (this) {
            track = currentTrack;
            g = generation;
        }
        if (track != null) {
            if (listening) {
                sendTrack(player, track, g);
            } else {
                player.getScheduler().run(plugin, t -> player.stopSound(SoundStop.named(track.soundKey())), null);
            }
        }
        return true;
    }

    private void loadMuted(DgMusicPlugin plugin) {
        if (!Files.isRegularFile(mutedFile)) return;
        try {
            for (String line : Files.readAllLines(mutedFile, StandardCharsets.UTF_8)) {
                try {
                    if (!line.isBlank()) muted.add(UUID.fromString(line.trim()));
                } catch (IllegalArgumentException ignored) {
                    // 跳过无法识别的行
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "读取 muted.txt 失败", e);
        }
    }

    private void saveMuted() {
        List<String> lines = muted.stream().map(UUID::toString).sorted().toList();
        Bukkit.getAsyncScheduler().runNow(plugin, t -> {
            synchronized (mutedFile) {
                try {
                    Files.write(mutedFile, lines, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "保存 muted.txt 失败", e);
                }
            }
        });
    }

    public void handleJoin(Player player) {
        if (!plugin.settings().playOnJoin() || muted.contains(player.getUniqueId())) return;
        PreparedTrack track;
        long g;
        synchronized (this) {
            track = currentTrack;
            g = generation;
        }
        if (track != null) sendTrack(player, track, g);
    }

    public void handleQuit(Player player) {
        // 断开连接时客户端会自动卸载服务器资源包
        loadedPacks.remove(player.getUniqueId());
    }

    public synchronized void shutdown() {
        generation++;
        cancelEndTask();
        queue.clear();
        current = null;
        PreparedTrack track = currentTrack;
        currentTrack = null;
        if (track != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    p.stopSound(SoundStop.named(track.soundKey()));
                } catch (Throwable ignored) {
                    // 关服过程中可能无法再操作玩家
                }
            }
        }
        loadedPacks.clear();
    }

    // ---- 内部状态机，调用方须持有锁 ----

    private boolean isQueued(Song song) {
        if (current != null && current.song().mid().equals(song.mid())) return true;
        for (Entry e : queue) {
            if (e.song().mid().equals(song.mid())) return true;
        }
        return false;
    }

    private void advance() {
        generation++;
        cancelEndTask();
        PreparedTrack old = currentTrack;
        currentTrack = null;
        current = null;
        if (old != null) {
            stopSoundForAll(old.soundKey());
        }

        Entry next = queue.pollFirst();
        if (next == null) {
            return;
        }
        current = next;
        long g = generation;
        if (!next.prepared().isDone()) {
            plugin.broadcast(Component.text("正在加载《" + next.song().displayName() + "》，请稍候…", NamedTextColor.GRAY));
        }
        next.prepared().whenComplete((track, err) -> onPrepared(g, next, track, err));
    }

    private synchronized void onPrepared(long g, Entry entry, PreparedTrack track, Throwable err) {
        if (g != generation) return;
        if (err != null) {
            Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
            String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            plugin.getLogger().warning("准备歌曲失败 " + entry.song().displayName() + ": " + reason);
            plugin.broadcast(Component.text("《" + entry.song().displayName() + "》播放失败：" + reason, NamedTextColor.RED));
            advance();
            return;
        }

        Settings s = plugin.settings();
        currentTrack = track;
        plugin.broadcast(Component.text()
                .append(Component.text("正在播放 ", NamedTextColor.GREEN))
                .append(Component.text("《" + entry.song().displayName() + "》", NamedTextColor.AQUA))
                .append(Component.text(" " + entry.song().formattedDuration(), NamedTextColor.DARK_GRAY))
                .append(Component.text("  点歌：" + entry.requesterName(), NamedTextColor.GRAY))
                .build());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!muted.contains(p.getUniqueId())) sendTrack(p, track, g);
        }

        int duration = entry.song().durationSeconds();
        if (duration <= 0 || duration > s.maxDurationSeconds()) duration = s.maxDurationSeconds();
        endTask = Bukkit.getAsyncScheduler().runDelayed(plugin, t -> onTrackEnd(g),
                duration + (long) s.endGraceSeconds(), TimeUnit.SECONDS);
    }

    private synchronized void onTrackEnd(long g) {
        if (g == generation) advance();
    }

    private void cancelEndTask() {
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
    }

    // ---- 玩家操作 ----

    private void sendTrack(Player player, PreparedTrack track, long g) {
        player.getScheduler().run(plugin, t -> {
            Deque<UUID> loaded = loadedPacks.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
            List<UUID> evict = new ArrayList<>();
            synchronized (loaded) {
                if (loaded.contains(track.packId())) {
                    // 最近加载过这首歌，直接播放，客户端不需要重新加载
                    loaded.remove(track.packId());
                    loaded.addLast(track.packId());
                    playIfCurrent(player, track, g);
                    return;
                }
                int max = plugin.settings().maxLoadedPacks();
                while (loaded.size() >= max) evict.add(loaded.pollFirst());
            }
            // 和新资源包同时发送，客户端只会重新加载一次
            for (UUID old : evict) player.removeResourcePacks(old);

            String url = plugin.packUrl(player, track.id());
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(track.packId(), URI.create(url), track.sha1());
            String prompt = plugin.settings().packPrompt();
            ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                    .packs(info)
                    .required(false)
                    .replace(false)
                    .callback((id, status, audience) -> onPackStatus(player, track, g, status, url));
            if (!prompt.isBlank()) request.prompt(MiniMessage.miniMessage().deserialize(prompt));
            player.sendResourcePacks(request.build());
        }, null);
    }

    private void onPackStatus(Player player, PreparedTrack track, long g, ResourcePackStatus status, String url) {
        switch (status) {
            case SUCCESSFULLY_LOADED -> {
                Deque<UUID> loaded = loadedPacks.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
                synchronized (loaded) {
                    loaded.remove(track.packId());
                    loaded.addLast(track.packId());
                }
                playIfCurrent(player, track, g);
            }
            case DECLINED -> {
                plugin.message(player, Component.text(
                        "你拒绝了音乐资源包，将听不到全服点歌。如不想再收到推送，可输入 /dgdont", NamedTextColor.YELLOW));
            }
            case INVALID_URL, FAILED_DOWNLOAD, FAILED_RELOAD, DISCARDED -> {
                plugin.getLogger().warning(player.getName() + " 加载音乐资源包失败: " + status
                        + "（玩家下载地址: " + url + "，请确认该地址能从外网访问，必要时在 http.public-url 中手动指定）");
                plugin.message(player, Component.text("音乐资源包加载失败（" + status + "）", NamedTextColor.RED));
            }
            default -> {
            }
        }
    }

    private void playIfCurrent(Player player, PreparedTrack track, long g) {
        if (g != generation || muted.contains(player.getUniqueId())) return;
        Settings s = plugin.settings();
        Sound sound = Sound.sound(track.soundKey(), s.soundSource(), s.volume(), 1.0f);
        player.getScheduler().run(plugin, t -> player.playSound(sound, Sound.Emitter.self()), null);
    }

    private void stopSoundForAll(Key key) {
        SoundStop stop = SoundStop.named(key);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(plugin, t -> p.stopSound(stop), null);
        }
    }
}
