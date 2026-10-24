package io.github.rvlion.dgmusic.command;

import io.github.rvlion.dgmusic.DgMusicPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * /dgqg 切歌、/dgstop 停止所有播放。
 */
public final class AdminCommands implements CommandExecutor {

    private final DgMusicPlugin plugin;

    public AdminCommands(DgMusicPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("dgqg")) {
            boolean skipped = plugin.player().skip(song -> plugin.broadcast(Component.text()
                    .append(Component.text(sender.getName(), NamedTextColor.YELLOW))
                    .append(Component.text(" 切掉了 ", NamedTextColor.GREEN))
                    .append(Component.text("《" + song.displayName() + "》", NamedTextColor.AQUA))
                    .build()));
            if (!skipped) {
                plugin.message(sender, Component.text("当前没有正在播放的歌曲", NamedTextColor.YELLOW));
            }
        } else {
            boolean stopped = plugin.player().stopAll(() -> plugin.broadcast(Component.text()
                    .append(Component.text(sender.getName(), NamedTextColor.YELLOW))
                    .append(Component.text(" 停止了所有歌曲播放并清空了点歌队列", NamedTextColor.RED))
                    .build()));
            if (!stopped) {
                plugin.message(sender, Component.text("当前没有正在播放或排队的歌曲", NamedTextColor.YELLOW));
            }
        }
        return true;
    }
}
