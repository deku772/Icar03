package com.icarme.lyrics;

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
 * 歌词抓取（车机联网模式）：三源降级 + 内存缓存。
 *  1) lrclib.net  2) music.163.com  3) c.y.qq.com
 * 与手机端同源策略，供 OverlayService 在「在线歌词」模式下使用。
 */
public final class CarLyricsFetcher {

    public static final class Result {
        public final String lrc;
        public final String tlyric;
        public final String source;

        public Result(String lrc, String tlyric, String source) {
            this.lrc = lrc;
            this.tlyric = tlyric;
            this.source = source;
        }
    }

    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_CANDIDATES = 3;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0 Mobile Safari/537.36 IcarLyrics/2.6";

    private final Map<String, Result> cache = new ConcurrentHashMap<>();
    public volatile String lastError = "";

    public Result fetch(String track, String artist, int durationSec) {
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

    private static String cleanText(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ").replace("<unknown>", "").trim();
    }

    private Result tryLrclib(String track, String artist, int durationSec, StringBuilder fail) {
        Result r = parseLrclib(httpGet(
                "https://lrclib.net/api/get?track_name=" + enc(track)
                        + "&artist_name=" + enc(artist), null), "lrclib");
        if (r != null) return r;
        if (!artist.isEmpty()) {
            r = parseLrclib(httpGet(
                    "https://lrclib.net/api/get?track_name=" + enc(track)
                            + "&artist_name=", null), "lrclib");
            if (r != null) return r;
        }
        try {
            String body = httpGet("https://lrclib.net/api/search?track_name=" + enc(track)
                    + (!artist.isEmpty() ? "&artist_name=" + enc(artist) : ""), null);
            if (body == null) {
                fail.append("lrclib超时; ");
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
            fail.append("lrclib无词; ");
        } catch (Exception e) {
            fail.append("lrclib异常; ");
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

    private Result tryNetease(String track, String artist, StringBuilder fail) {
        try {
            String q = track + (artist.isEmpty() ? "" : " " + artist);
            JSONArray songs = neteaseSearch("https://music.163.com/api/cloudsearch/pc?s="
                    + enc(q) + "&type=1&limit=8&offset=0");
            if (songs == null || songs.length() == 0) {
                songs = neteaseSearch("https://music.163.com/api/search/get/web?s="
                        + enc(q) + "&type=1&limit=8");
            }
            if (songs == null || songs.length() == 0) {
                fail.append("网易云无结果; ");
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
            fail.append("网易云无时间轴词; ");
        } catch (Exception e) {
            fail.append("网易云异常; ");
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

    private Result tryQq(String track, String artist, StringBuilder fail) {
        try {
            String q = track + (artist.isEmpty() ? "" : " " + artist);
            String searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
                    + "?ct=24&qqmusic_ver=1251&new_json=1&remoteplace=txt.yqq.top"
                    + "&p=1&n=8&w=" + enc(q) + "&format=json";
            String body = httpGet(searchUrl, "https://y.qq.com/");
            if (body == null) {
                fail.append("QQ搜索失败; ");
                return null;
            }
            JSONObject data = new JSONObject(body).optJSONObject("data");
            JSONObject song = data != null ? data.optJSONObject("song") : null;
            JSONArray list = song != null ? song.optJSONArray("list") : null;
            if (list == null || list.length() == 0) {
                fail.append("QQ无结果; ");
                return null;
            }
            int tried = 0;
            for (int i = 0; i < list.length() && tried < MAX_CANDIDATES; i++) {
                JSONObject s = list.optJSONObject(i);
                if (s == null) continue;
                String mid = s.optString("songmid", "");
                if (mid.isEmpty()) {
                    JSONObject info = s.optJSONObject("info");
                    if (info != null) {
                        JSONObject md = info.optJSONObject("musicData");
                        if (md != null) mid = md.optString("songmid", "");
                    }
                }
                if (mid.isEmpty()) continue;
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
            fail.append("QQ无时间轴词; ");
        } catch (Exception e) {
            fail.append("QQ异常; ");
        }
        return null;
    }

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
            if (conn.getResponseCode() != 200) return null;
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
