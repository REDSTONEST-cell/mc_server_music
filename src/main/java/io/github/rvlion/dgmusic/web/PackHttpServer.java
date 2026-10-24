package io.github.rvlion.dgmusic.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 只读的资源包下载服务：GET /packs/&lt;id&gt;.zip
 */
public final class PackHttpServer {

    private static final Pattern FILE_NAME = Pattern.compile("[0-9a-f]{16}\\.zip");

    private final HttpServer server;
    private final ExecutorService executor;
    private final Path packDir;

    public PackHttpServer(String bind, int port, Path packDir) throws IOException {
        this.packDir = packDir;
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "DgMusic-HTTP-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/packs/", this::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            Path file = packDir.resolve(name);
            if (!FILE_NAME.matcher(name).matches() || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            long size = Files.size(file);
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(size));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, size);
            try (OutputStream out = exchange.getResponseBody()) {
                Files.copy(file, out);
            }
        }
    }
}
