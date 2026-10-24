package io.github.rvlion.dgmusic.command;

import io.github.rvlion.dgmusic.DgMusicPlugin;
import io.github.rvlion.dgmusic.Settings;
import io.github.rvlion.dgmusic.player.MusicPlayer;
import io.github.rvlion.dgmusic.qq.QQMusicException;
import io.github.rvlion.dgmusic.qq.Song;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /dgss 搜歌、/dgxz 选歌。
 */
public final class SearchCommands implements CommandExecutor {

    private static final int MAX_KEYWORD_LENGTH = 60;

    private final DgMusicPlugin plugin;
    private final Map<UUID, List<Song>> results = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSearch = new ConcurrentHashMap<>();

    public SearchCommands(DgMusicPlugin plugin) {
        this.plugin = plugin;
    }

    public void forget(Player player) {
        results.remove(player.getUniqueId());
        lastSearch.remove(player.getUniqueId());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.prefixed(Component.text("只有玩家可以点歌", NamedTextColor.RED)));
            return true;
        }
        if (command.getName().equalsIgnoreCase("dgss")) {
            search(player, args);
        } else {
            select(player, args);
        }
        return true;
    }

    private void search(Player player, String[] args) {
        if (args.length == 0) {
            plugin.message(player, Component.text("用法：/dgss <歌曲名>", NamedTextColor.YELLOW));
            return;
        }
        String keyword = String.join(" ", args).trim();
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            keyword = keyword.substring(0, MAX_KEYWORD_LENGTH);
        }

        Settings s = plugin.settings();
        if (!player.hasPermission("dgmusic.bypass") && s.searchCooldownSeconds() > 0) {
            long now = System.currentTimeMillis();
            Long last = lastSearch.get(player.getUniqueId());
            long wait = last == null ? 0 : last + s.searchCooldownSeconds() * 1000L - now;
            if (wait > 0) {
                plugin.message(player, Component.text("搜索太频繁，请 " + ((wait + 999) / 1000) + " 秒后再试", NamedTextColor.RED));
                return;
            }
            lastSearch.put(player.getUniqueId(), now);
        }

        String query = keyword;
        plugin.message(player, Component.text("正在搜索「" + query + "」…", NamedTextColor.GRAY));
        Bukkit.getAsyncScheduler().runNow(plugin, t -> {
            try {
                List<Song> songs = plugin.client().search(query, s.searchResults(), s.qqCookie(), s.timeoutSeconds());
                if (songs.isEmpty()) {
                    plugin.message(player, Component.text("没有找到「" + query + "」相关的歌曲", NamedTextColor.YELLOW));
                    return;
                }
                results.put(player.getUniqueId(), songs);
                showResults(player, query, songs);
            } catch (QQMusicException e) {
                plugin.getLogger().warning("搜索失败: " + e.getMessage());
                plugin.message(player, Component.text("搜索失败：" + e.getMessage(), NamedTextColor.RED));
            }
        });
    }

    private void showResults(Player player, String query, List<Song> songs) {
        TextComponent.Builder builder = Component.text()
                .append(plugin.prefixed(Component.text("「" + query + "」的搜索结果：", NamedTextColor.GREEN)));
        for (int i = 0; i < songs.size(); i++) {
            Song song = songs.get(i);
            int index = i + 1;
            Component hover = Component.text()
                    .append(Component.text(song.name(), NamedTextColor.AQUA))
                    .append(Component.newline())
                    .append(Component.text("歌手：" + song.singers(), NamedTextColor.GRAY))
                    .append(song.album().isEmpty() ? Component.empty()
                            : Component.newline().append(Component.text("专辑：" + song.album(), NamedTextColor.GRAY)))
                    .append(Component.newline())
                    .append(Component.text("时长：" + song.formattedDuration(), NamedTextColor.GRAY))
                    .append(Component.newline())
                    .append(Component.text("点击点播", NamedTextColor.YELLOW))
                    .build();
            Component line = Component.text()
                    .append(Component.text("[" + index + "] ", NamedTextColor.GOLD))
                    .append(Component.text(song.name(), NamedTextColor.WHITE))
                    .append(Component.text(song.singers().isEmpty() ? "" : " - " + song.singers(), NamedTextColor.GRAY))
                    .append(Component.text(" " + song.formattedDuration(), NamedTextColor.DARK_GRAY))
                    .append(song.vip() ? Component.text(" [VIP]", NamedTextColor.LIGHT_PURPLE) : Component.empty())
                    .clickEvent(ClickEvent.runCommand("/dgxz " + index))
                    .hoverEvent(HoverEvent.showText(hover))
                    .build();
            builder.append(Component.newline()).append(line);
        }
        builder.append(Component.newline())
                .append(Component.text("点击歌曲或输入 /dgxz <序号> 点播", NamedTextColor.GRAY));
        Component message = builder.build();
        player.getScheduler().run(plugin, t -> player.sendMessage(message), null);
    }

    private void select(Player player, String[] args) {
        List<Song> songs = results.get(player.getUniqueId());
        if (songs == null) {
            plugin.message(player, Component.text("请先使用 /dgss <歌曲名> 搜索歌曲", NamedTextColor.YELLOW));
            return;
        }
        if (args.length != 1) {
            plugin.message(player, Component.text("用法：/dgxz <序号>", NamedTextColor.YELLOW));
            return;
        }
        int index;
        try {
            index = Integer.parseInt(args[0].trim());
        } catch (NumberFormatException e) {
            index = -1;
        }
        if (index < 1 || index > songs.size()) {
            plugin.message(player, Component.text("序号无效，请输入 1~" + songs.size(), NamedTextColor.RED));
            return;
        }

        Song song = songs.get(index - 1);
        Settings s = plugin.settings();
        if (song.durationSeconds() > s.maxDurationSeconds()) {
            plugin.message(player, Component.text("这首歌太长了（超过 " + (s.maxDurationSeconds() / 60) + " 分钟），换一首吧",
                    NamedTextColor.RED));
            return;
        }

        String name = "《" + song.displayName() + "》";
        plugin.player().enqueue(song, player, player.hasPermission("dgmusic.bypass"),
                outcome -> announce(player, name, s, outcome));
    }

    private void announce(Player player, String name, Settings s, MusicPlayer.Outcome outcome) {
        switch (outcome.result()) {
            case STARTED -> plugin.broadcast(Component.text()
                    .append(Component.text(player.getName(), NamedTextColor.YELLOW))
                    .append(Component.text(" 点播了 ", NamedTextColor.GREEN))
                    .append(Component.text(name, NamedTextColor.AQUA))
                    .build());
            case QUEUED -> plugin.broadcast(Component.text()
                    .append(Component.text(player.getName(), NamedTextColor.YELLOW))
                    .append(Component.text(" 点播了 ", NamedTextColor.GREEN))
                    .append(Component.text(name, NamedTextColor.AQUA))
                    .append(Component.text("，排在第 " + outcome.position() + " 位", NamedTextColor.GREEN))
                    .build());
            case DUPLICATE -> plugin.message(player, Component.text(name + " 已经在播放或排队中了", NamedTextColor.YELLOW));
            case QUEUE_FULL -> plugin.message(player, Component.text("点歌队列已满（" + s.queueMaxSize() + " 首），请稍后再点",
                    NamedTextColor.RED));
            case PLAYER_LIMIT -> plugin.message(player, Component.text("你最多同时点 " + s.queueMaxPerPlayer()
                    + " 首歌，等前面的播完再来吧", NamedTextColor.RED));
        }
    }
}
