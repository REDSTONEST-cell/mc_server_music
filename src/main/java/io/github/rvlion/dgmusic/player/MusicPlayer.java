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

import java.net.URI;
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

    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    /** 每个玩家当前持有的本插件资源包 */
    private final Map<UUID, UUID> sentPacks = new ConcurrentHashMap<>();

    public MusicPlayer(DgMusicPlugin plugin, TrackPreparer preparer) {
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

    /** 切换个人静音，返回切换后是否为静音状态。 */
    public boolean toggleMute(Player player) {
        UUID uuid = player.getUniqueId();
        if (muted.remove(uuid)) {
            PreparedTrack track;
            long g;
            synchronized (this) {
                track = currentTrack;
                g = generation;
            }
            if (track != null) sendTrack(player, track, g);
            return false;
        }
        muted.add(uuid);
        PreparedTrack track;
        synchronized (this) {
            track = currentTrack;
        }
        UUID pack = sentPacks.remove(uuid);
        player.getScheduler().run(plugin, t -> {
            if (track != null) player.stopSound(SoundStop.named(track.soundKey()));
            if (pack != null) player.removeResourcePacks(pack);
        }, null);
        return true;
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
        sentPacks.remove(player.getUniqueId());
    }

    public synchronized void shutdown() {
        generation++;
        cancelEndTask();
        queue.clear();
        current = null;
        PreparedTrack track = currentTrack;
        currentTrack = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                if (track != null) p.stopSound(SoundStop.named(track.soundKey()));
                UUID pack = sentPacks.get(p.getUniqueId());
                if (pack != null) p.removeResourcePacks(pack);
            } catch (Throwable ignored) {
                // 关服过程中可能无法再操作玩家
            }
        }
        sentPacks.clear();
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
            removePacksForAll();
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
            UUID old = sentPacks.put(player.getUniqueId(), track.packId());
            if (track.packId().equals(old)) {
                // 玩家已经加载过这个资源包（例如同一首歌再次点播）
                playIfCurrent(player, track, g);
                return;
            }
            if (old != null) player.removeResourcePacks(old);

            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(track.packId(), URI.create(track.url()), track.sha1());
            String prompt = plugin.settings().packPrompt();
            ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                    .packs(info)
                    .required(false)
                    .replace(false)
                    .callback((id, status, audience) -> onPackStatus(player, track, g, status));
            if (!prompt.isBlank()) request.prompt(MiniMessage.miniMessage().deserialize(prompt));
            player.sendResourcePacks(request.build());
        }, null);
    }

    private void onPackStatus(Player player, PreparedTrack track, long g, ResourcePackStatus status) {
        switch (status) {
            case SUCCESSFULLY_LOADED -> playIfCurrent(player, track, g);
            case DECLINED -> {
                sentPacks.remove(player.getUniqueId(), track.packId());
                plugin.message(player, Component.text(
                        "你拒绝了音乐资源包，将听不到全服点歌。如不想再收到推送，可使用 /dgmusic mute", NamedTextColor.YELLOW));
            }
            case INVALID_URL, FAILED_DOWNLOAD, FAILED_RELOAD, DISCARDED -> {
                sentPacks.remove(player.getUniqueId(), track.packId());
                plugin.getLogger().warning(player.getName() + " 加载音乐资源包失败: " + status
                        + "（请检查 http.public-url 是否能被玩家访问: " + track.url() + "）");
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

    private void removePacksForAll() {
        for (Map.Entry<UUID, UUID> e : new ArrayList<>(sentPacks.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            sentPacks.remove(e.getKey(), e.getValue());
            if (p != null) {
                UUID pack = e.getValue();
                p.getScheduler().run(plugin, t -> p.removeResourcePacks(pack), null);
            }
        }
    }
}
