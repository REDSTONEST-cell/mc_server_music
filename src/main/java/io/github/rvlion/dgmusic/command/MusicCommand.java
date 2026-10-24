package io.github.rvlion.dgmusic.command;

import io.github.rvlion.dgmusic.DgMusicPlugin;
import io.github.rvlion.dgmusic.player.MusicPlayer;
import io.github.rvlion.dgmusic.qq.QQMusicClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /dgmusic list | reload，/dgdont 拒绝听歌，/dgon 重新开启
 */
public final class MusicCommand implements TabExecutor {

    private final DgMusicPlugin plugin;

    public MusicCommand(DgMusicPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("dgdont") || command.getName().equalsIgnoreCase("dgon")) {
            setListening(sender, command.getName().equalsIgnoreCase("dgon"));
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                if (check(sender, "dgmusic.list")) list(sender);
            }
            case "reload" -> {
                if (!check(sender, "dgmusic.reload")) return true;
                String error = plugin.reload();
                if (error != null) {
                    plugin.message(sender, Component.text("config.yml 格式有误，已保留原配置：" + error, NamedTextColor.RED));
                    plugin.message(sender, Component.text(
                            "提示：Windows 路径请用正斜杠（C:/ffmpeg/bin/ffmpeg.exe），或用单引号包住", NamedTextColor.YELLOW));
                    return true;
                }
                plugin.message(sender, Component.text("配置已重新加载，QQ 音乐 Cookie: "
                        + QQMusicClient.describeCookie(plugin.settings().qqCookie()), NamedTextColor.GREEN));
            }
            default -> help(sender);
        }
        return true;
    }

    private void setListening(CommandSender sender, boolean listening) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.prefixed(Component.text("只有玩家可以使用", NamedTextColor.RED)));
            return;
        }
        boolean changed = plugin.player().setListening(player, listening);
        if (listening) {
            plugin.message(player, changed
                    ? Component.text("已开启点歌收听，之后会收到全服点歌", NamedTextColor.GREEN)
                    : Component.text("你已经在收听点歌了", NamedTextColor.YELLOW));
        } else {
            plugin.message(player, changed
                    ? Component.text("已拒绝听歌：不会再推送音乐资源包，也不会播放点歌。输入 /dgon 可重新开启", NamedTextColor.YELLOW)
                    : Component.text("你已经拒绝听歌了，输入 /dgon 可重新开启", NamedTextColor.YELLOW));
        }
    }

    private boolean check(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        plugin.message(sender, Component.text("你没有权限", NamedTextColor.RED));
        return false;
    }

    private void list(CommandSender sender) {
        MusicPlayer.Snapshot snap = plugin.player().snapshot();
        TextComponent.Builder b = Component.text().append(plugin.prefixed(Component.text("点歌队列", NamedTextColor.GREEN)));
        if (snap.current() == null) {
            b.append(Component.newline()).append(Component.text("当前没有正在播放的歌曲", NamedTextColor.GRAY));
        } else {
            b.append(Component.newline())
                    .append(Component.text(snap.playing() ? "▶ 正在播放 " : "… 正在加载 ", NamedTextColor.GREEN))
                    .append(Component.text("《" + snap.current().song().displayName() + "》", NamedTextColor.AQUA))
                    .append(Component.text("  点歌：" + snap.current().requesterName(), NamedTextColor.GRAY));
        }
        int i = 1;
        for (MusicPlayer.Entry e : snap.queue()) {
            b.append(Component.newline())
                    .append(Component.text(i++ + ". ", NamedTextColor.GOLD))
                    .append(Component.text("《" + e.song().displayName() + "》", NamedTextColor.WHITE))
                    .append(Component.text("  点歌：" + e.requesterName(), NamedTextColor.GRAY));
        }
        Component message = b.build();
        if (sender instanceof Player p) {
            p.getScheduler().run(plugin, t -> p.sendMessage(message), null);
        } else {
            sender.sendMessage(message);
        }
    }

    private void help(CommandSender sender) {
        Component message = Component.text()
                .append(plugin.prefixed(Component.text("全服点歌", NamedTextColor.GREEN)))
                .append(Component.newline()).append(Component.text("/dgss <歌曲名>  搜索歌曲", NamedTextColor.GRAY))
                .append(Component.newline()).append(Component.text("/dgxz <序号>  点播搜索结果中的歌曲", NamedTextColor.GRAY))
                .append(Component.newline()).append(Component.text("/dgqg  切歌", NamedTextColor.GRAY))
                .append(Component.newline()).append(Component.text("/dgmusic list  查看点歌队列", NamedTextColor.GRAY))
                .append(Component.newline()).append(Component.text("/dgdont  拒绝听歌（不再推送资源包）", NamedTextColor.GRAY))
                .append(Component.newline()).append(Component.text("/dgon  重新开启听歌", NamedTextColor.GRAY))
                .append(Component.newline()).append(Component.text("/dgstop  停止所有播放（管理员）", NamedTextColor.GRAY))
                .append(Component.newline()).append(Component.text("/dgmusic reload  重载配置（管理员）", NamedTextColor.GRAY))
                .build();
        if (sender instanceof Player p) {
            p.getScheduler().run(plugin, t -> p.sendMessage(message), null);
        } else {
            sender.sendMessage(message);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        if (!command.getName().equalsIgnoreCase("dgmusic")) return List.of();
        return List.of("list", "reload").stream()
                .filter(s -> s.startsWith(prefix))
                .filter(s -> !s.equals("reload") || sender.hasPermission("dgmusic.reload"))
                .toList();
    }
}
