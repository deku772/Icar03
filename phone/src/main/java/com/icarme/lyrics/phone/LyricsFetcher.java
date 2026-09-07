package com.icarme.lyrics.phone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 歌词抓取：三源降级 + 内存缓存。
 *
 *  1) lrclib.net   — 无鉴权，优先，可能带逐字增强时间轴（<mm:ss.xx>）与翻译
 *  2) music.163.com — 网易云公开接口，lrc + tlyric
 *  3) c.y.qq.com   — QQ 音乐 fcg 接口，兜底
 *
 * 结果缓存按 "track|artist" 键存内存，命中直接返回，避免频控。
 */
final class LyricsFetcher {

    static final class Result {
        final String lrc;      /* 标准 LRC */
        final String tlyric;   /* 翻译 LRC，可为 null */
        final String source;   /* lrclib / netease / qq */

        Result(String lrc, String tlyric, String source) {
            this.lrc = lrc;
            this.tlyric = tlyric;
            this.source = source;
        }
    }

    private static final int TIMEOUT_MS = 8000;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0 Mobile Safari/537.36 IcarLyrics/1.0";

    private final Map<String, Result> cache = new ConcurrentHashMap<>();

    /** 主入口：按降级链取词，全部失败返回 null */
    Result fetch(String track, String artist, int durationSec) {
        if (track == null || track.trim().isEmpty()) return null;
        String key = track + "|" + (artist == null ? "" : artist);
        Result cached = cache.get(key);
        if (cached != null) return cached;

        Result r = fromLrclib(track, artist, durationSec);
        if (r == null) r = fromNetease(track, artist);
        if (r == null) r = fromQq(track, artist);

        if (r != null && r.lrc != null && !r.lrc.isEmpty()) {
            cache.put(key, r);
            return r;
        }
        return null;
    }

    void clearCache() { cache.clear(); }

    /* ---------------- 1) lrclib ---------------- */

    private Result fromLrclib(String track, String artist, int durationSec) {
        try {
            String url = "https://lrclib.net/api/get?track_name="
                    + URLEncoder.encode(track, "UTF-8")
                    + "&artist_name=" + URLEncoder.encode(empty(artist), "UTF-8");
            String body = httpGet(url, null);
            if (body == null && artist != null && !artist.isEmpty()) {
                /* 歌手带多余信息时降级为仅歌名精确查询 */
                url = "https://lrclib.net/api/get?track_name="
                        + URLEncoder.encode(track, "UTF-8") + "&artist_name=";
                body = httpGet(url, null);
            }
            if (body == null) {
                body = searchLrclib(track, artist, durationSec);
            }
            if (body == null) return null;

            JSONObject obj = new JSONObject(body);
            String synced = obj.optString("syncedLyrics", "");
            String plain = obj.optString("plainLyrics", "");
            String source = obj.optString("source", "lrclib");
            if (synced != null && !synced.isEmpty()) {
                return new Result(synced, null, "lrclib");
            }
            if (plain != null && !plain.isEmpty()) {
                /* 无时间轴纯文本：包一层每行 3 秒的伪 LRC，渲染端按行均分亦可 */
                return new Result(plainToLrc(plain), null, "lrclib");
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String searchLrclib(String track, String artist, int durationSec) throws Exception {
        String url = "https://lrclib.net/api/search?track_name="
                + URLEncoder.encode(track, "UTF-8");
        if (artist != null && !artist.isEmpty()) {
            url += "&artist_name=" + URLEncoder.encode(artist, "UTF-8");
        }
        String body = httpGet(url, null);
        if (body == null) return null;
        JSONArray arr = new JSONArray(body);
        int best = -1, bestScore = -1;
        for (int i = 0; i < arr.length() && i < 10; i++) {
            JSONObject o = arr.getJSONObject(i);
            int score = 0;
            if (artist != null && !artist.isEmpty()
                    && o.optString("artistName", "").toLowerCase().contains(artist.toLowerCase())) {
                score += 2;
            }
            int dur = (int) Math.round(o.optDouble("duration", -1));
            if (durationSec > 0 && dur > 0 && Math.abs(dur - durationSec) <= 3) score += 2;
            if (!o.optString("syncedLyrics", "").isEmpty()) score += 1;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return (best >= 0) ? arr.getJSONObject(best).toString() : null;
    }

    private static String plainToLrc(String plain) {
        String[] lines = plain.split("\n");
        StringBuilder sb = new StringBuilder();
        int t = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            sb.append(String.format("[%02d:%02d.%03d]%s%n", t / 60, t % 60, 0, line.trim()));
            t += 3;
        }
        return sb.toString();
    }

    /* ---------------- 2) 网易云 ---------------- */

    private Result fromNetease(String track, String artist) {
        try {
            String searchUrl = "https://music.163.com/api/search/get/web?s="
                    + URLEncoder.encode(track + " " + empty(artist), "UTF-8")
                    + "&type=1&limit=5";
            String body = httpGet(searchUrl, "https://music.163.com/");
            if (body == null) return null;
            JSONObject obj = new JSONObject(body);
            JSONArray songs = obj.optJSONObject("result").optJSONArray("songs");
            if (songs == null || songs.length() == 0) return null;

            long songId = songs.getJSONObject(0).optLong("id", -1);
            if (songId <= 0) return null;

            String lyricUrl = "https://music.163.com/api/song/lyric?id=" + songId + "&lv=1&tv=1";
            String lyricBody = httpGet(lyricUrl, "https://music.163.com/");
            if (lyricBody == null) return null;
            JSONObject lobj = new JSONObject(lyricBody);
            String lrc = lobj.optJSONObject("lrc") != null
                    ? lobj.optJSONObject("lrc").optString("lyric", "") : "";
            String tlyric = lobj.optJSONObject("tlyric") != null
                    ? lobj.optJSONObject("tlyric").optString("lyric", "") : "";
            if (lrc.isEmpty()) return null;
            return new Result(lrc, tlyric.isEmpty() ? null : tlyric, "netease");
        } catch (Exception ignored) {}
        return null;
    }

    /* ---------------- 3) QQ 音乐 ---------------- */

    private Result fromQq(String track, String artist) {
        try {
            String q = URLEncoder.encode(track + " " + empty(artist), "UTF-8");
            String searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
                    + "?ct=24&qqmusic_ver=1251&new_json=1&remoteplace=txt.yqq.top"
                    + "&searchid=0&t=0&aggr=1&cr=1&catZhida=1&lossless=0&flag_qc=0"
                    + "&p=1&n=5&w=" + q + "&format=json";
            String body = httpGet(searchUrl, "https://y.qq.com/");
            if (body == null) return null;
            JSONObject obj = new JSONObject(body);
            JSONArray songs = obj.getJSONObject("data").getJSONObject("song")
                    .optJSONArray("list");
            if (songs == null || songs.length() == 0) return null;
            String mid = songs.getJSONObject(0).optString("mid", "");
            if (mid.isEmpty()) return null;

            String lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
                    + "?songmid=" + mid + "&g_tk=5381&format=json&nobase64=1";
            String lyricBody = httpGet(lyricUrl, "https://y.qq.com/");
            if (lyricBody == null) return null;
            JSONObject lobj = new JSONObject(lyricBody);
            String lrc = lobj.optString("lyric", "");
            String trans = lobj.optString("trans", "");
            if (lrc.isEmpty()) return null;
            return new Result(lrc, trans.isEmpty() ? null : trans, "qq");
        } catch (Exception ignored) {}
        return null;
    }

    /* ---------------- HTTP ---------------- */

    private static String empty(String s) { return s == null ? "" : s; }

    private static String httpGet(String url, String referer) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            if (referer != null) conn.setRequestProperty("Referer", referer);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
