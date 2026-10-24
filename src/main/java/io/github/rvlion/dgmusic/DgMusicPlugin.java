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
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
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
        String configError = validateConfig();
        if (configError != null) {
            getLogger().severe("config.yml 格式错误，已使用默认配置: " + configError);
            getLogger().severe("提示：Windows 路径请用正斜杠（C:/ffmpeg/bin/ffmpeg.exe）或改用单引号包住");
        }
        settings = Settings.load(getConfig());

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
        player = new MusicPlayer(this, preparer, getDataFolder().toPath());
        startHttpServer();

        searchCommands = new SearchCommands(this);
        AdminCommands adminCommands = new AdminCommands(this);
        register("dgss", searchCommands);
        register("dgxz", searchCommands);
        register("dgqg", adminCommands);
        register("dgstop", adminCommands);
        MusicCommand musicCommand = new MusicCommand(this);
        register("dgmusic", musicCommand);
        register("dgdont", musicCommand);
        register("dgon", musicCommand);
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

    /**
     * 重新加载配置。配置文件格式错误时保留当前配置并返回错误信息，成功返回 null。
     */
    public String reload() {
        String error = validateConfig();
        if (error != null) {
            getLogger().warning("config.yml 格式错误，未重新加载: " + error);
            return error;
        }
        Settings old = settings;
        reloadConfig();
        settings = Settings.load(getConfig());
        if (!old.httpBind().equals(settings.httpBind()) || old.httpPort() != settings.httpPort()) {
            if (httpServer != null) httpServer.stop();
            startHttpServer();
        }
        checkEnvironment();
        return null;
    }

    /** 检查 config.yml 能否被解析，返回错误描述（含行号），正常返回 null。 */
    private String validateConfig() {
        try {
            new YamlConfiguration().load(new File(getDataFolder(), "config.yml"));
            return null;
        } catch (IOException | InvalidConfigurationException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return message.replaceAll("\\s+", " ").trim();
        }
    }

    private void startHttpServer() {
        try {
            httpServer = new PackHttpServer(settings.httpBind(), settings.httpPort(), preparer.packDir());
            httpServer.start();
            getLogger().info("资源包 HTTP 服务已启动: " + settings.httpBind() + ":" + settings.httpPort() + "，玩家下载地址: "
                    + (settings.publicUrl().isEmpty() ? "http://<玩家进服地址>:" + settings.httpPort() : settings.publicUrl())
                    + "（用其他网络的浏览器打开，显示 DgMusic OK 即为可访问）");
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
        if (s.publicUrl().contains("://0.0.0.0") || s.publicUrl().contains("://127.0.0.1")
                || s.publicUrl().contains("://localhost")) {
            getLogger().warning("http.public-url = " + s.publicUrl() + " 是本机地址，玩家无法访问。请改为公网 IP 或域名，或留空自动使用玩家进服地址");
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

    /**
     * 玩家下载资源包的地址。未配置 http.public-url 时使用玩家进服时填写的地址 + HTTP 端口。
     */
    public String packUrl(Player player, String id) {
        String base = settings.publicUrl();
        if (base.isEmpty()) {
            String host = null;
            InetSocketAddress virtualHost = player.getVirtualHost();
            if (virtualHost != null) host = virtualHost.getHostString();
            if (host == null || host.isBlank()) host = Bukkit.getIp();
            if (host == null || host.isBlank()) host = "127.0.0.1";
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            base = "http://" + host + ":" + settings.httpPort();
        }
        return base + "/packs/" + id + ".zip";
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
