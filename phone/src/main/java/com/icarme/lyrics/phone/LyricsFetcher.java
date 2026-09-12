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
 *  1) lrclib.net   — 无鉴权，优先，可能带逐字增强时间轴（<mm:ss.xx>）
 *  2) music.163.com — 网易云公开接口，搜索多首再取 lrc + tlyric
 *  3) c.y.qq.com   — QQ 音乐公开接口，兜底
 *
 * v2.5：每源最多试 3 首搜索结果（避免第一首无词/匹配错就整源失败）；
 * 失败原因写入 lastError，主界面取词行可见。
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
    private static final int MAX_CANDIDATES = 3;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0 Mobile Safari/537.36 IcarLyrics/1.0";

    private final Map<String, Result> cache = new ConcurrentHashMap<>();
    /** 最近一次 fetch 的失败摘要（诊断用，空=上次成功或未尝试） */
    volatile String lastError = "";

    /** 主入口：按降级链取词，全部失败返回 null */
    Result fetch(String track, String artist, int durationSec) {
        if (track == null || track.trim().isEmpty()) {
            lastError = "曲目名为空";
            return null;
        }
        String key = track + "|" + (artist == null ? "" : artist);
        Result cached = cache.get(key);
        if (cached != null) {
            lastError = "";
            return cached;
        }

        String art = cleanText(artist);
        String trk = cleanText(track);
        StringBuilder fail = new StringBuilder();

        Result r = tryLrclib(trk, art, durationSec, fail);
        if (r == null) r = tryNetease(trk, art, fail);
        if (r == null) r = tryQq(trk, art, fail);

        if (r != null && r.lrc != null && !r.lrc.isEmpty()) {
            cache.put(key, r);
            lastError = "";
            return r;
        }
        lastError = fail.length() > 0 ? fail.toString() : "三源均无结果";
        return null;
    }

    void clearCache() { cache.clear(); }

    private static String cleanText(String s) {
        if (s == null) return "";
        return s.trim()
                .replaceAll("\\s+", " ")
                .replace("<unknown>", "")
                .trim();
    }

    /* ---------------- 1) lrclib ---------------- */

    private Result tryLrclib(String track, String artist, int durationSec, StringBuilder fail) {
        /* 精确 get */
        Result r = parseLrclib(httpGet(
                "https://lrclib.net/api/get?track_name=" + enc(track)
                        + "&artist_name=" + enc(artist), null), "lrclib");
        if (r != null) return r;

        /* 歌手带多余信息时：仅歌名精确查询 */
        if (!artist.isEmpty()) {
            r = parseLrclib(httpGet(
                    "https://lrclib.net/api/get?track_name=" + enc(track)
                            + "&artist_name=", null), "lrclib");
            if (r != null) return r;
        }

        /* 搜索取最佳 */
        try {
            String body = httpGet("https://lrclib.net/api/search?track_name=" + enc(track)
                    + (!artist.isEmpty() ? "&artist_name=" + enc(artist) : ""), null);
            if (body == null) {
                fail.append("lrclib超时/网络; ");
                return null;
            }
            JSONArray arr = new JSONArray(body);
            int scored = pickLrclibBest(arr, artist, durationSec);
            if (scored < 0) {
                fail.append("lrclib无匹配; ");
                return null;
            }
            r = parseLrclib(arr.getJSONObject(scored).toString(), "lrclib");
            if (r != null) return r;
            fail.append("lrclib有结果但无词; ");
        } catch (Exception e) {
            fail.append("lrclib异常(").append(e.getClass().getSimpleName()).append("); ");
        }
        return null;
    }

    private static int pickLrclibBest(JSONArray arr, String artist, int durationSec) {
        int best = -1, bestScore = -1;
        for (int i = 0; i < arr.length() && i < 10; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int score = 0;
            String an = o.optString("artistName", "");
            if (!artist.isEmpty() && an.toLowerCase().contains(artist.toLowerCase())) score += 2;
            else if (!artist.isEmpty() && artist.toLowerCase().contains(an.toLowerCase()) && !an.isEmpty()) score += 1;
            int dur = (int) Math.round(o.optDouble("duration", -1));
            if (durationSec > 0 && dur > 0 && Math.abs(dur - durationSec) <= 3) score += 2;
            if (!o.optString("syncedLyrics", "").isEmpty()) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static Result parseLrclib(String body, String source) {
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(body);
            String synced = obj.optString("syncedLyrics", "");
            String plain = obj.optString("plainLyrics", "");
            if (synced != null && !synced.isEmpty()) return new Result(synced, null, source);
            if (plain != null && !plain.isEmpty()) return new Result(plainToLrc(plain), null, source);
        } catch (Exception ignored) {}
        return null;
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

    private Result tryNetease(String track, String artist, StringBuilder fail) {
        try {
            String q = track + (artist.isEmpty() ? "" : " " + artist);
            /* cloudsearch 比旧 get/web 更稳；失败再退回旧接口 */
            JSONArray songs = neteaseSearch("https://music.163.com/api/cloudsearch/pc?s="
                    + enc(q) + "&type=1&limit=8&offset=0");
            if (songs == null || songs.length() == 0) {
                songs = neteaseSearch("https://music.163.com/api/search/get/web?s="
                        + enc(q) + "&type=1&limit=8");
            }
            if (songs == null || songs.length() == 0) {
                fail.append("网易云无搜索结果; ");
                return null;
            }

            int tried = 0;
            for (int i = 0; i < songs.length() && tried < MAX_CANDIDATES; i++) {
                JSONObject song = songs.optJSONObject(i);
                if (song == null) continue;
                long songId = song.optLong("id", -1);
                if (songId <= 0) continue;
                tried++;
                String lyricBody = httpGet(
                        "https://music.163.com/api/song/lyric?id=" + songId + "&lv=1&kv=1&tv=-1",
                        "https://music.163.com/");
                if (lyricBody == null) continue;
                try {
                    JSONObject lobj = new JSONObject(lyricBody);
                    JSONObject lrcObj = lobj.optJSONObject("lrc");
                    String lrc = lrcObj != null ? lrcObj.optString("lyric", "") : "";
                    if (lrc.isEmpty() || !hasTimedLine(lrc)) continue;
                    JSONObject tObj = lobj.optJSONObject("tlyric");
                    String tlyric = tObj != null ? tObj.optString("lyric", "") : "";
                    return new Result(lrc, tlyric.isEmpty() ? null : tlyric, "netease");
                } catch (Exception ignored) {}
            }
            fail.append("网易云").append(tried > 0 ? "候选均无时间轴词; " : "无有效id; ");
        } catch (Exception e) {
            fail.append("网易云异常(").append(e.getClass().getSimpleName()).append("); ");
        }
        return null;
    }

    private static JSONArray neteaseSearch(String url) {
        String body = httpGet(url, "https://music.163.com/");
        if (body == null) return null;
        try {
            JSONObject obj = new JSONObject(body);
            JSONObject result = obj.optJSONObject("result");
            if (result == null) return null;
            return result.optJSONArray("songs");
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- 3) QQ 音乐 ---------------- */

    private Result tryQq(String track, String artist, StringBuilder fail) {
        try {
            String q = track + (artist.isEmpty() ? "" : " " + artist);
            JSONArray songs = qqSearch(q);
            if (songs == null || songs.length() == 0) {
                fail.append("QQ无搜索结果; ");
                return null;
            }

            int tried = 0;
            for (int i = 0; i < songs.length() && tried < MAX_CANDIDATES; i++) {
                JSONObject song = songs.optJSONObject(i);
                if (song == null) continue;
                String mid = song.optString("songmid", "");
                if (mid == null || mid.isEmpty()) {
                    /* 兼容嵌套结构 */
                    JSONObject info = song.optJSONObject("info");
                    if (info != null) {
                        JSONObject music = info.optJSONObject("musicData");
                        if (music != null) mid = music.optString("songmid", "");
                    }
                }
                if (mid == null || mid.isEmpty()) continue;
                tried++;
                String lyricBody = httpGet(
                        "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
                                + "?songmid=" + mid + "&g_tk=5381&format=json&nobase64=1",
                        "https://y.qq.com/");
                if (lyricBody == null) continue;
                try {
                    JSONObject lobj = new JSONObject(lyricBody);
                    String lrc = lobj.optString("lyric", "");
                    if (lrc.isEmpty() || !hasTimedLine(lrc)) continue;
                    String trans = lobj.optString("trans", "");
                    return new Result(lrc, trans.isEmpty() ? null : trans, "qq");
                } catch (Exception ignored) {}
            }
            fail.append("QQ").append(tried > 0 ? "候选均无时间轴词; " : "无有效mid; ");
        } catch (Exception e) {
            fail.append("QQ异常(").append(e.getClass().getSimpleName()).append("); ");
        }
        return null;
    }

    private static JSONArray qqSearch(String q) {
        String searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
                + "?ct=24&qqmusic_ver=1251&new_json=1&remoteplace=txt.yqq.top"
                + "&searchid=&t=0&aggr=1&cr=1&catZhida=1&lossless=0&flag_qc=0"
                + "&p=1&n=8&w=" + enc(q) + "&format=json";
        String body = httpGet(searchUrl, "https://y.qq.com/");
        if (body == null) return null;
        try {
            JSONObject obj = new JSONObject(body);
            JSONObject data = obj.optJSONObject("data");
            if (data == null) return null;
            JSONObject song = data.optJSONObject("song");
            if (song == null) return null;
            return song.optJSONArray("list");
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- 工具 ---------------- */

    private static boolean hasTimedLine(String lrc) {
        if (lrc == null || lrc.isEmpty()) return false;
        for (String ln : lrc.split("\n")) {
            if (ln.matches(".*\\[\\d+:\\d+(?:\\.\\d+)?\\].*")) return true;
        }
        return false;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String httpGet(String url, String referer) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (referer != null) {
                conn.setRequestProperty("Referer", referer);
                conn.setRequestProperty("Origin", referer.replaceAll("/$", ""));
            }
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
