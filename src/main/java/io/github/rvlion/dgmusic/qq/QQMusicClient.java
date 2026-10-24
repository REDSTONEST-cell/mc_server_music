package io.github.rvlion.dgmusic.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * QQ音乐网页/客户端接口（非官方）的最小封装：搜索歌曲、获取播放链接。
 */
public final class QQMusicClient {

    private static final URI API = URI.create("https://u.y.qq.com/cgi-bin/musicu.fcg");
    private static final String DEFAULT_SIP = "https://isure.stream.qqmusic.qq.com/";
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final HttpClient http;

    public QQMusicClient() {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public HttpClient http() {
        return http;
    }

    public List<Song> search(String keyword, int limit, String cookie, int timeoutSeconds) throws QQMusicException {
        Credential cred = Credential.parse(cookie);

        JsonObject comm = new JsonObject();
        comm.addProperty("ct", "11");
        comm.addProperty("cv", "1003006");
        comm.addProperty("uin", cred.uin());

        JsonObject param = new JsonObject();
        param.addProperty("search_type", 0);
        param.addProperty("query", keyword);
        param.addProperty("page_num", 1);
        param.addProperty("num_per_page", limit);
        param.addProperty("grp", 1);

        JsonObject req = new JsonObject();
        req.addProperty("module", "music.search.SearchCgiService");
        req.addProperty("method", "DoSearchForQQMusicMobile");
        req.add("param", param);

        JsonObject body = new JsonObject();
        body.add("comm", comm);
        body.add("req", req);

        JsonObject resp = post(body, cred, timeoutSeconds);
        JsonObject result = checkModule(resp, "req");
        JsonObject data = obj(result, "data");
        JsonObject songBody = obj(data, "body");
        JsonArray items = songBody.has("item_song") && songBody.get("item_song").isJsonArray()
                ? songBody.getAsJsonArray("item_song") : new JsonArray();

        List<Song> songs = new ArrayList<>();
        for (JsonElement el : items) {
            if (!el.isJsonObject()) continue;
            JsonObject s = el.getAsJsonObject();
            String mid = str(s, "mid");
            if (mid.isEmpty()) continue;

            String name = str(s, "name");
            if (name.isEmpty()) name = str(s, "title");

            List<String> singerNames = new ArrayList<>();
            if (s.has("singer") && s.get("singer").isJsonArray()) {
                for (JsonElement singer : s.getAsJsonArray("singer")) {
                    if (singer.isJsonObject()) {
                        String n = str(singer.getAsJsonObject(), "name");
                        if (!n.isEmpty()) singerNames.add(n);
                    }
                }
            }

            JsonObject file = obj(s, "file");
            String mediaMid = str(file, "media_mid");
            if (mediaMid.isEmpty()) mediaMid = mid;

            JsonObject pay = obj(s, "pay");
            boolean vip = num(pay, "pay_play") == 1;

            songs.add(new Song(mid, mediaMid, name, String.join("/", singerNames),
                    str(obj(s, "album"), "name"), num(s, "interval"), vip));
            if (songs.size() >= limit) break;
        }
        return songs;
    }

    /**
     * 按音质优先级获取播放链接。
     *
     * @return 可直接下载的音频 URL 与对应文件扩展名
     */
    public PlayUrl resolvePlayUrl(Song song, List<String> qualities, String cookie, int timeoutSeconds)
            throws QQMusicException {
        Credential cred = Credential.parse(cookie);
        if (!cred.loggedIn()) {
            throw new QQMusicException("未配置 QQ 音乐 Cookie，无法获取播放链接，请联系管理员");
        }

        JsonArray songMids = new JsonArray();
        JsonArray songTypes = new JsonArray();
        JsonArray fileNames = new JsonArray();
        Map<String, String> extByFile = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (String q : qualities) {
            String ext = extensionOf(q);
            if (ext == null) continue;
            String fileName = q + song.mid() + song.mediaMid() + ext;
            songMids.add(song.mid());
            songTypes.add(0);
            fileNames.add(fileName);
            extByFile.put(fileName, ext);
            order.add(fileName);
        }
        if (order.isEmpty()) {
            throw new QQMusicException("配置的音质 qq.qualities 无效");
        }

        JsonObject comm = new JsonObject();
        comm.addProperty("uin", cred.uin());
        comm.addProperty("authst", cred.key());
        comm.addProperty("tmeLoginType", cred.loginType());
        comm.addProperty("ct", "24");
        comm.addProperty("cv", "0");
        comm.addProperty("format", "json");

        JsonObject param = new JsonObject();
        param.addProperty("guid", String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)));
        param.add("songmid", songMids);
        param.add("songtype", songTypes);
        param.add("filename", fileNames);
        param.addProperty("uin", cred.uin());
        param.addProperty("loginflag", 1);
        param.addProperty("platform", "20");

        JsonObject req = new JsonObject();
        req.addProperty("module", "vkey.GetVkeyServer");
        req.addProperty("method", "CgiGetVkey");
        req.add("param", param);

        JsonObject body = new JsonObject();
        body.add("comm", comm);
        body.add("req_1", req);

        JsonObject resp = post(body, cred, timeoutSeconds);
        JsonObject result = resp.has("req_1") && resp.get("req_1").isJsonObject()
                ? resp.getAsJsonObject("req_1") : null;
        if (result == null) {
            throw new QQMusicException("QQ音乐接口返回异常");
        }
        int code = num(result, "code");
        if (code == 1000) {
            throw new QQMusicException("QQ音乐登录已失效（code=1000），请管理员更新 Cookie");
        }
        if (code != 0) {
            throw new QQMusicException("获取播放链接失败（code=" + code + "）");
        }

        JsonObject data = obj(result, "data");
        String sip = DEFAULT_SIP;
        if (data.has("sip") && data.get("sip").isJsonArray() && !data.getAsJsonArray("sip").isEmpty()) {
            String first = data.getAsJsonArray("sip").get(0).getAsString();
            if (!first.isBlank()) sip = first;
        }
        if (!sip.endsWith("/")) sip += "/";

        Map<String, String> purlByFile = new HashMap<>();
        if (data.has("midurlinfo") && data.get("midurlinfo").isJsonArray()) {
            for (JsonElement el : data.getAsJsonArray("midurlinfo")) {
                if (!el.isJsonObject()) continue;
                JsonObject info = el.getAsJsonObject();
                String purl = str(info, "purl");
                if (!purl.isEmpty()) purlByFile.put(str(info, "filename"), purl);
            }
        }
        for (String fileName : order) {
            String purl = purlByFile.get(fileName);
            if (purl != null) {
                String url = purl.startsWith("http") ? purl : sip + purl;
                return new PlayUrl(url, extByFile.get(fileName));
            }
        }
        throw new QQMusicException(song.vip()
                ? "无法获取播放链接：该歌曲需要会员，或 Cookie 对应账号没有权限"
                : "无法获取播放链接：该歌曲可能因版权原因无法播放");
    }

    private JsonObject post(JsonObject body, Credential cred, int timeoutSeconds) throws QQMusicException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(API)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Referer", "https://y.qq.com/")
                .header("Origin", "https://y.qq.com")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (!cred.rawCookie().isEmpty()) {
            builder.header("Cookie", cred.rawCookie());
        }
        try {
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new QQMusicException("QQ音乐接口 HTTP " + resp.statusCode());
            }
            JsonElement parsed = JsonParser.parseString(resp.body());
            if (!parsed.isJsonObject()) {
                throw new QQMusicException("QQ音乐接口返回异常");
            }
            return parsed.getAsJsonObject();
        } catch (IOException e) {
            throw new QQMusicException("连接QQ音乐失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QQMusicException("请求被中断", e);
        } catch (RuntimeException e) {
            throw new QQMusicException("解析QQ音乐返回数据失败", e);
        }
    }

    private static JsonObject checkModule(JsonObject resp, String key) throws QQMusicException {
        if (!resp.has(key) || !resp.get(key).isJsonObject()) {
            throw new QQMusicException("QQ音乐接口返回异常");
        }
        JsonObject module = resp.getAsJsonObject(key);
        int code = num(module, "code");
        if (code != 0) {
            throw new QQMusicException("QQ音乐接口错误（code=" + code + "）");
        }
        return module;
    }

    private static String extensionOf(String quality) {
        if (quality.length() < 4) return null;
        return switch (quality.substring(0, 1).toUpperCase(Locale.ROOT)) {
            case "M" -> ".mp3";
            case "C" -> ".m4a";
            case "O" -> ".ogg";
            case "F" -> ".flac";
            default -> null;
        };
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return "";
        return o.get(key).getAsString();
    }

    private static int num(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return 0;
        try {
            return o.get(key).getAsInt();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public record PlayUrl(String url, String extension) {
    }

    /**
     * 从网页版 Cookie 中解析出的登录信息。
     */
    record Credential(String uin, String key, String loginType, String rawCookie) {

        boolean loggedIn() {
            return !uin.equals("0") && !key.isEmpty();
        }

        static Credential parse(String cookie) {
            if (cookie == null) cookie = "";
            Map<String, String> map = new HashMap<>();
            for (String part : cookie.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
            String uin = firstNonEmpty(map.get("uin"), map.get("qqmusic_uin"), map.get("wxuin"));
            uin = uin.replaceFirst("^o0*", "");
            if (uin.isEmpty() || !uin.chars().allMatch(Character::isDigit)) uin = "0";
            String key = firstNonEmpty(map.get("qqmusic_key"), map.get("qm_keyst"));
            String loginType = firstNonEmpty(map.get("tmeLoginType"), map.containsKey("wxuin") ? "1" : "2");
            return new Credential(uin, key, loginType, cookie.trim());
        }

        private static String firstNonEmpty(String... values) {
            for (String v : values) {
                if (v != null && !v.isEmpty()) return v;
            }
            return "";
        }
    }
}
