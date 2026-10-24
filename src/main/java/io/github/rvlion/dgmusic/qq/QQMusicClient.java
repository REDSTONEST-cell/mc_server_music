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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * QQ音乐网页/客户端接口（非官方）的最小封装：搜索歌曲、获取播放链接。
 */
public final class QQMusicClient {

    private static final String API = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    /** 网页端带签名的通道，比 musicu 更不容易被限流 */
    private static final String SIGNED_API = "https://u.y.qq.com/cgi-bin/musics.fcg";
    private static final String DEFAULT_SIP = "https://isure.stream.qqmusic.qq.com/";
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    /** 接口限流/风控时间歇返回的业务码，稍等重试即可 */
    private static final int CODE_BUSY = 2001;
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 300;

    private static final String SEARCH_MODULE = "music.search.SearchCgiService";

    private static final int[] SIGN_PART_1 = {23, 14, 6, 36, 16, 7, 19};
    private static final int[] SIGN_PART_2 = {16, 1, 32, 12, 19, 27, 8, 5};
    private static final int[] SIGN_SCRAMBLE = {89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133,
            143, 161, 121, 179};

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

    // ---- 搜索 ----

    /**
     * 匿名搜索。依次尝试：签名通道桌面搜索 → 普通通道桌面搜索 → 移动端搜索。
     */
    public List<Song> search(String keyword, int limit, int timeoutSeconds) throws QQMusicException {
        QQMusicException firstError = null;
        for (int i = 0; i < 3; i++) {
            try {
                List<Song> songs = i < 2
                        ? searchDesktop(keyword, limit, timeoutSeconds, i == 0)
                        : searchMobile(keyword, limit, timeoutSeconds);
                if (!songs.isEmpty() || i == 2) return songs;
            } catch (QQMusicException e) {
                if (firstError == null) firstError = e;
            }
        }
        throw firstError;
    }

    private List<Song> searchDesktop(String keyword, int limit, int timeoutSeconds, boolean signed)
            throws QQMusicException {
        JsonObject param = new JsonObject();
        param.addProperty("search_type", 0);
        param.addProperty("query", keyword);
        param.addProperty("page_num", 1);
        param.addProperty("num_per_page", limit);

        // 该模块在网页通道下必须以模块名作为 key，且不能带顶层 comm，否则返回空数据
        JsonObject body = new JsonObject();
        body.add(SEARCH_MODULE, request(SEARCH_MODULE, "DoSearchForQQMusicDesktop", param));

        JsonObject data = obj(call(body, SEARCH_MODULE, Credential.ANONYMOUS, timeoutSeconds, signed), "data");
        return parseSongs(element(obj(obj(data, "body"), "song"), "list"), limit);
    }

    private List<Song> searchMobile(String keyword, int limit, int timeoutSeconds) throws QQMusicException {
        JsonObject comm = new JsonObject();
        comm.addProperty("ct", "11");
        comm.addProperty("cv", "1003006");
        comm.addProperty("uin", "0");

        JsonObject param = new JsonObject();
        param.addProperty("search_type", 0);
        param.addProperty("query", keyword);
        param.addProperty("page_num", 1);
        param.addProperty("num_per_page", limit);
        param.addProperty("grp", 1);

        JsonObject body = new JsonObject();
        body.add("comm", comm);
        body.add("req", request(SEARCH_MODULE, "DoSearchForQQMusicMobile", param));

        JsonObject data = obj(call(body, "req", Credential.ANONYMOUS, timeoutSeconds, false), "data");
        return parseSongs(element(obj(data, "body"), "item_song"), limit);
    }

    private static List<Song> parseSongs(JsonElement list, int limit) {
        List<Song> songs = new ArrayList<>();
        if (list == null || !list.isJsonArray()) return songs;
        for (JsonElement el : list.getAsJsonArray()) {
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

            boolean vip = num(obj(s, "pay"), "pay_play") == 1;
            songs.add(new Song(mid, str(obj(s, "file"), "media_mid"), name, String.join("/", singerNames),
                    str(obj(s, "album"), "name"), num(s, "interval"), vip));
            if (songs.size() >= limit) break;
        }
        return songs;
    }

    // ---- 播放链接 ----

    /**
     * 按音质优先级获取播放链接。
     *
     * @return 可直接下载的音频 URL 与对应文件扩展名
     */
    public PlayUrl resolvePlayUrl(Song song, List<String> qualities, String cookie, int timeoutSeconds)
            throws QQMusicException {
        Credential cred = Credential.parse(cookie);
        String problem = cred.problem();
        if (problem != null) {
            throw new QQMusicException(problem + "，无法获取播放链接，请联系管理员");
        }

        List<String> fileNames = new ArrayList<>();
        Map<String, String> extByFile = new HashMap<>();
        for (String q : qualities) {
            String ext = extensionOf(q);
            if (ext == null) continue;
            String fileName = song.mediaMid().isEmpty()
                    ? q + song.mid() + song.mid() + ext
                    : q + song.mediaMid() + ext;
            fileNames.add(fileName);
            extByFile.put(fileName, ext);
        }
        if (fileNames.isEmpty()) {
            throw new QQMusicException("配置的音质 qq.qualities 无效");
        }

        QQMusicException error = null;
        for (boolean signed : new boolean[]{true, false}) {
            for (String[] api : new String[][]{{"music.vkey.GetVkey", "UrlGetVkey"}, {"vkey.GetVkeyServer", "CgiGetVkey"}}) {
                try {
                    PlayUrl url = requestVkey(api[0], api[1], song, fileNames, extByFile, cred, timeoutSeconds, signed);
                    if (url != null) return url;
                } catch (QQMusicException e) {
                    if (error == null) error = e;
                }
            }
        }
        if (error != null) throw error;
        // 接口在 Cookie 失效时也可能只返回空链接，无法区分具体原因
        throw new QQMusicException(song.vip()
                ? "无法获取播放链接：该歌曲需要会员，请确认 Cookie 对应账号有会员且 Cookie 未失效"
                : "无法获取播放链接：Cookie 可能已失效，或该歌曲因版权原因无法播放");
    }

    private PlayUrl requestVkey(String module, String method, Song song, List<String> fileNames,
                                Map<String, String> extByFile, Credential cred, int timeoutSeconds,
                                boolean signed) throws QQMusicException {
        JsonArray songMids = new JsonArray();
        JsonArray songTypes = new JsonArray();
        JsonArray files = new JsonArray();
        for (String fileName : fileNames) {
            songMids.add(song.mid());
            songTypes.add(0);
            files.add(fileName);
        }

        JsonObject param = new JsonObject();
        param.addProperty("uin", cred.uin());
        param.addProperty("guid", UUID.randomUUID().toString().replace("-", ""));
        param.add("songmid", songMids);
        param.add("songtype", songTypes);
        param.add("filename", files);
        param.addProperty("loginflag", 1);
        param.addProperty("platform", "20");
        param.addProperty("ctx", 0);

        JsonObject body = new JsonObject();
        body.add("comm", webComm(cred));
        body.add("req_0", request(module, method, param));

        JsonObject data = obj(call(body, "req_0", cred, timeoutSeconds, signed), "data");
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
        for (String fileName : fileNames) {
            String purl = purlByFile.get(fileName);
            if (purl != null) {
                String url = purl.startsWith("http") ? purl : sip + purl;
                return new PlayUrl(url, extByFile.get(fileName));
            }
        }
        return null;
    }

    /** 网页端登录态的通用 comm 参数 */
    private static JsonObject webComm(Credential cred) {
        long gtk = cred.gtk();
        JsonObject comm = new JsonObject();
        comm.addProperty("ct", 24);
        comm.addProperty("cv", 4747474);
        comm.addProperty("platform", "yqq.json");
        comm.addProperty("chid", "0");
        comm.addProperty("uin", cred.uin());
        comm.addProperty("authst", cred.key());
        comm.addProperty("tmeLoginType", cred.loginType());
        comm.addProperty("g_tk", gtk);
        comm.addProperty("g_tk_new_20200303", gtk);
        comm.addProperty("format", "json");
        comm.addProperty("inCharset", "utf-8");
        comm.addProperty("outCharset", "utf-8");
        comm.addProperty("notice", 0);
        comm.addProperty("need_new_code", 1);
        return comm;
    }

    // ---- 请求 ----

    /**
     * 发送请求并取出指定 key 的模块结果；遇到 2001 时短暂等待后重试。
     */
    private JsonObject call(JsonObject body, String key, Credential cred, int timeoutSeconds, boolean signed)
            throws QQMusicException {
        int code = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            JsonObject resp = post(body, cred, timeoutSeconds, signed);
            code = num(resp, "code");
            JsonObject module = resp.has(key) && resp.get(key).isJsonObject() ? resp.getAsJsonObject(key) : null;
            if (code == 0 && module != null) {
                code = num(module, "code");
                if (code == 0) return module;
            }
            if (code != CODE_BUSY) break;
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new QQMusicException("请求被中断", e);
                }
            }
        }
        throw new QQMusicException(describe(code));
    }

    private static String describe(int code) {
        return switch (code) {
            case CODE_BUSY -> "QQ音乐接口繁忙（code=2001），请稍后再试";
            case 1000 -> "QQ音乐登录已失效（code=1000），请管理员更新 Cookie";
            case 104003 -> "Cookie 缺少播放票据（code=104003），请在 y.qq.com 重新登录后复制完整 Cookie";
            default -> "QQ音乐接口错误（code=" + code + "）";
        };
    }

    private JsonObject post(JsonObject body, Credential cred, int timeoutSeconds, boolean signed)
            throws QQMusicException {
        String payload = body.toString();
        URI uri = URI.create(signed
                ? SIGNED_API + "?_=" + System.currentTimeMillis() + "&sign=" + sign(payload)
                : API);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Referer", "https://y.qq.com/")
                .header("Origin", "https://y.qq.com")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
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

    /** 网页端请求签名（zzc 算法），对实际发送的请求体计算 */
    static String sign(String payload) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-1").digest(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String hash = HexFormat.of().withUpperCase().formatHex(digest);
        StringBuilder sb = new StringBuilder("zzc");
        for (int i : SIGN_PART_1) sb.append(hash.charAt(i));
        byte[] scrambled = new byte[SIGN_SCRAMBLE.length];
        for (int i = 0; i < scrambled.length; i++) {
            scrambled[i] = (byte) (SIGN_SCRAMBLE[i] ^ Integer.parseInt(hash.substring(i * 2, i * 2 + 2), 16));
        }
        sb.append(Base64.getEncoder().encodeToString(scrambled).replaceAll("[\\\\/+=]", ""));
        for (int i : SIGN_PART_2) sb.append(hash.charAt(i));
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static JsonObject request(String module, String method, JsonObject param) {
        JsonObject req = new JsonObject();
        req.addProperty("module", module);
        req.addProperty("method", method);
        req.add("param", param);
        return req;
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

    private static JsonElement element(JsonObject o, String key) {
        return o != null && o.has(key) ? o.get(key) : null;
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

    /** 用于日志的 Cookie 识别结果，不包含任何票据内容。 */
    public static String describeCookie(String cookie) {
        Credential cred = Credential.parse(cookie);
        String problem = cred.problem();
        if (problem != null) return problem;
        String masked = cred.uin().length() > 4
                ? "*".repeat(cred.uin().length() - 4) + cred.uin().substring(cred.uin().length() - 4)
                : cred.uin();
        return "已识别账号 " + masked + "（" + (cred.loginType().equals("1") ? "微信" : "QQ") + "登录）";
    }

    /**
     * 从网页版 Cookie 中解析出的登录信息。
     */
    record Credential(String uin, String key, String loginType, String rawCookie) {

        static final Credential ANONYMOUS = new Credential("0", "", "2", "");

        boolean loggedIn() {
            return problem() == null;
        }

        /** Cookie 不可用的原因，可用时返回 null */
        String problem() {
            if (rawCookie.isEmpty()) return "未配置 qq.cookie";
            if (uin.equals("0")) return "qq.cookie 中缺少 uin（微信登录为 wxuin）";
            if (key.isEmpty()) return "qq.cookie 中缺少 qqmusic_key 或 qm_keyst，可能复制不完整";
            return null;
        }

        long gtk() {
            long h = 5381;
            for (int i = 0; i < key.length(); i++) {
                h = (h << 5) + h + key.charAt(i);
            }
            return h & 0x7fffffffL;
        }

        static Credential parse(String cookie) {
            if (cookie == null) cookie = "";
            // 兼容整行复制「Cookie: xxx」或带引号、换行的情况
            cookie = cookie.replace("\r", "").replace("\n", "").trim();
            if (cookie.regionMatches(true, 0, "cookie:", 0, 7)) cookie = cookie.substring(7).trim();
            if (cookie.length() >= 2 && (cookie.startsWith("\"") && cookie.endsWith("\"")
                    || cookie.startsWith("'") && cookie.endsWith("'"))) {
                cookie = cookie.substring(1, cookie.length() - 1).trim();
            }
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
            String loginType = firstNonEmpty(map.get("tmeLoginType"), key.startsWith("W_X") ? "1" : "2");
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
