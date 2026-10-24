package io.github.rvlion.dgmusic;

import io.github.rvlion.dgmusic.audio.TrackPreparer;
import io.github.rvlion.dgmusic.command.AdminCommands;
import io.github.rvlion.dgmusic.command.MusicCommand;
import io.github.rvlion.dgmusic.command.SearchCommands;
import io.github.rvlion.dgmusic.player.MusicPlayer;
import io.github.rvlion.dgmusic.qq.QQMusicClient;
import io.github.rvlion.dgmusic.web.PackHttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class DgMusicPlugin extends JavaPlugin implements Listener {

    private volatile Settings settings;
    private ExecutorService prepareExecutor;
    private QQMusicClient client;
    private TrackPreparer preparer;
    private PackHttpServer httpServer;
    private MusicPlayer player;
    private SearchCommands searchCommands;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig(), Bukkit.getIp());

        AtomicInteger counter = new AtomicInteger();
        prepareExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "DgMusic-Prepare-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        client = new QQMusicClient();
        try {
            preparer = new TrackPreparer(client, prepareExecutor, getDataFolder().toPath(), getLogger(),
                    this::settings, () -> player.activeIds());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "无法创建数据目录", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        player = new MusicPlayer(this, preparer);
        startHttpServer();

        searchCommands = new SearchCommands(this);
        AdminCommands adminCommands = new AdminCommands(this);
        register("dgss", searchCommands);
        register("dgxz", searchCommands);
        register("dgqg", adminCommands);
        register("dgstop", adminCommands);
        register("dgmusic", new MusicCommand(this));
        Bukkit.getPluginManager().registerEvents(this, this);

        checkEnvironment();
    }

    @Override
    public void onDisable() {
        if (player != null) player.shutdown();
        if (httpServer != null) httpServer.stop();
        if (prepareExecutor != null) {
            prepareExecutor.shutdownNow();
            try {
                prepareExecutor.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void reload() {
        Settings old = settings;
        reloadConfig();
        settings = Settings.load(getConfig(), Bukkit.getIp());
        if (!old.httpBind().equals(settings.httpBind()) || old.httpPort() != settings.httpPort()) {
            if (httpServer != null) httpServer.stop();
            startHttpServer();
        }
        checkEnvironment();
    }

    private void startHttpServer() {
        try {
            httpServer = new PackHttpServer(settings.httpBind(), settings.httpPort(), preparer.packDir());
            httpServer.start();
            getLogger().info("资源包 HTTP 服务已启动: " + settings.httpBind() + ":" + settings.httpPort()
                    + "，玩家下载地址: " + settings.publicUrl());
        } catch (IOException e) {
            httpServer = null;
            getLogger().log(Level.SEVERE, "无法启动资源包 HTTP 服务（端口 " + settings.httpPort() + " 被占用？），点歌将无法播放", e);
        }
    }

    private void checkEnvironment() {
        Settings s = settings;
        String cookieStatus = QQMusicClient.describeCookie(s.qqCookie());
        if (cookieStatus.startsWith("已识别")) {
            getLogger().info("QQ 音乐 Cookie: " + cookieStatus);
        } else {
            getLogger().warning(cookieStatus + "，只能搜索，无法播放。请在 " + getDataFolder().toPath().resolve("config.yml")
                    + " 中填写 QQ 音乐 Cookie 后执行 /dgmusic reload");
        }
        if (getConfig().getString("http.public-url", "").isBlank()) {
            getLogger().warning("未配置 http.public-url，玩家可能无法下载音乐资源包。当前使用: " + s.publicUrl());
        }
        Bukkit.getAsyncScheduler().runNow(this, t -> {
            try {
                Process p = new ProcessBuilder(s.ffmpeg(), "-hide_banner", "-encoders")
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes());
                p.waitFor(10, TimeUnit.SECONDS);
                if (!out.contains("libvorbis")) {
                    getLogger().warning("ffmpeg 不支持 libvorbis 编码器，无法转码音乐");
                }
            } catch (IOException e) {
                getLogger().warning("找不到 ffmpeg（audio.ffmpeg = " + s.ffmpeg() + "），请先在服务器上安装 ffmpeg");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void register(String name, CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) return;
        command.setExecutor(executor);
        if (executor instanceof TabCompleter completer) command.setTabCompleter(completer);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        player.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        player.handleQuit(event.getPlayer());
        searchCommands.forget(event.getPlayer());
    }

    // ---- 公共工具 ----

    public Settings settings() {
        return settings;
    }

    public QQMusicClient client() {
        return client;
    }

    public MusicPlayer player() {
        return player;
    }

    public Component prefixed(Component message) {
        return MiniMessage.miniMessage().deserialize(settings.prefix()).append(message);
    }

    /** 线程安全地给某个命令发送者发送消息。 */
    public void message(CommandSender sender, Component message) {
        Component full = prefixed(message);
        if (sender instanceof Player p) {
            p.getScheduler().run(this, t -> p.sendMessage(full), null);
        } else {
            sender.sendMessage(full);
        }
    }

    /** 向全服玩家和控制台广播。 */
    public void broadcast(Component message) {
        Component full = prefixed(message);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(this, t -> p.sendMessage(full), null);
        }
        Bukkit.getConsoleSender().sendMessage(full);
    }
}
