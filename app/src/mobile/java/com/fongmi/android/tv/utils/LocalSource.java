package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地源文件夹：完全复刻 MuseTV 的本地源系统（com.muse.android.tv 的 LocalSource）。
 * <p>
 * 流程：设置页选几个手机里的文件夹 → 后台线程按后缀和最大深度扫描 → 把扫到的
 * 源文件解析成点播源 / 直播源 → 与模板合并成一份本地源配置 → 写到第一个扫描目录下
 * （默认叫“墨本地源.json”）→ 把这份配置当作点播配置加载。
 * <p>
 * 与 MuseTV 保持一致的关键点：
 * <ul>
 * <li>不用 SAF / DocumentFile，直接读普通文件路径（java.io.File），依赖 App 的存储权限；</li>
 * <li>全部配置存在一条 SharedPreferences JSON 里（local_source_settings），另有
 * local_source_enabled / local_source_auto 两个开关；</li>
 * <li>生成文件超过 8MB 不落盘；内容没变不重写；目标文件是用户自己写的配置时绝不覆盖；</li>
 * <li>同 key 的源按文件名 + 大小 + MD5 折叠，折不掉的改 key_1、key_2……；</li>
 * <li>源名字里的 [分类] 会按文件夹名收口，不在文件夹名里的分类会被删掉。</li>
 * </ul>
 * <p>
 * 与 MuseTV 不同的地方（已注明）：默认模板是墨影视自己的空模板（不带 MuseTV 的
 * 解析服务、默认直播源和蜘蛛 jar）；.jar 源在 MuseTV 里走它自己的 :8901 文件服务，
 * 墨影视没有这个服务，改用 file:// 地址；missav / Nostr / cf-proxy 这类
 * MuseTV 专属的特判没有搬过来。
 */
public class LocalSource {

    // ------------------------------------------------------------------ prefs

    public static final String KEY_ENABLED = "local_source_enabled";
    public static final String KEY_AUTO = "local_source_auto";
    public static final String KEY_SETTINGS = "local_source_settings";
    public static final String KEY_FILE = "local_source_file";
    public static final String KEY_SUMMARY = "local_source_summary";
    public static final String KEY_DIAGNOSIS = "local_source_diagnosis";
    private static final String KEY_LEGACY_DIR = "local_source_dir";

    private static final int MAX_DEPTH_DEFAULT = 5;
    private static final int MAX_FILES = 20000;
    private static final int MAX_SITES = 4000;
    private static final int MAX_LIVES = 2000;
    private static final int MAX_JSON_CHARS = 8 * 1024 * 1024;
    private static final long MAX_RULE_BYTES = 2L * 1024L * 1024L;

    private static final String[] DEFAULT_EXTENSIONS = {"py", "js", "php", "html", "json", "txt", "m3u", "db", "zip", "pkg"};
    private static final Set<String> BLOCKED_DEFAULT = new HashSet<>(Arrays.asList(
            ".git", ".idea", "labeditor", "lib", "pycache", "webview"));
    private static final String[] EXCLUDED_DEFAULT = {"config.php", "FileExplorer.php", "index.php", "ss.json", "start.php", "start.py", "T4Proxy.php", "test_runner.php"};
    private static final String[] DIR_PQ = {"PQ类"};
    private static final String[] DIR_YQ = {"YQ类"};
    private static final String[] DIR_JS = {"JS[Drpy]", "JS类", "JS", "drpy"};
    private static final String[] DIR_NOT_JS = {"cat类", "cat"};
    private static final String LIVE_DIR_NAME = "直播文件";
    private static final Pattern CATEGORY_PATTERN = Pattern.compile("\\[([^\\]]+)]");

    /** 模板里自带的解析等内容；墨影视模板默认是空的，只保留结构。 */
    private static JsonObject template;
    private static String lastJson = "";
    private static long lastWriteAt;
    private static boolean refreshScheduled;
    private static volatile int siteCount;
    /** 最近一次被挡住的写盘路径（目标是用户自己写的配置），读一次就清掉。 */
    private static String blockedPath = "";

    /** 扫描结果汇总：最近一次生成出的站点数（0 表示没扫到源）。 */
    public static int getSiteCount() {
        return siteCount;
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        Prefers.put(KEY_ENABLED, enabled);
    }

    public static boolean isAuto() {
        return Prefers.getBoolean(KEY_AUTO, true);
    }

    public static void setAuto(boolean auto) {
        Prefers.put(KEY_AUTO, auto);
    }

    // -------------------------------------------------------------- settings

    /** 与 MuseTV 的 S0() 一致：模板 DEFAULTS + 默认值，再叠加已保存的配置。 */
    public static JsonObject settings() {
        JsonObject json = new JsonObject();
        try {
            JsonObject defaults = template();
            if (defaults.has("DEFAULTS") && defaults.get("DEFAULTS").isJsonObject()) {
                json = defaults.getAsJsonObject("DEFAULTS").deepCopy();
            }
        } catch (Throwable ignored) {
        }
        json.addProperty("out_name", "墨本地源");
        json.add("scan_dirs", new JsonArray());
        json.add("scan_enabled_dirs", new JsonArray());
        json.add("scan_extensions", new JsonArray());
        json.add("blocked_dirs", new JsonArray());
        if (!json.has("parse_entries")) json.add("parse_entries", defaultParses());
        if (!json.has("replace_sites")) json.addProperty("replace_sites", false);
        json.addProperty("decode_base64", true);
        json.addProperty("max_depth", 5);
        try {
            String text = Prefers.getString(KEY_SETTINGS, "");
            if (!TextUtils.isEmpty(text)) {
                JsonElement element = JsonParser.parseString(text);
                if (element.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                        json.add(entry.getKey(), entry.getValue().deepCopy());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (stringList(json, "scan_dirs").isEmpty()) {
            String legacy = Prefers.getString(KEY_LEGACY_DIR, "");
            if (!TextUtils.isEmpty(legacy)) {
                JsonArray dirs = new JsonArray();
                dirs.add(legacy);
                json.add("scan_dirs", dirs);
                JsonArray enabled = new JsonArray();
                enabled.add(legacy);
                json.add("scan_enabled_dirs", enabled);
            }
        }
        return json;
    }

    /** 写一个设置字段（与 MuseTV 的 I(key, value) 一致），启用状态下触发自动回写。 */
    public static void put(String key, JsonElement value) {
        JsonObject json = settings();
        json.add(key, value);
        save(json);
        if (isEnabled() && !refreshScheduled) {
            refreshScheduled = true;
            new Thread(LocalSource::refresh, "local-source-autowrite").start();
        }
    }

    public static void save(JsonObject json) {
        Prefers.put(KEY_SETTINGS, json.toString());
    }

    /** 默认解析条目：模板 EXTRA.parses，每条默认启用。 */
    public static JsonArray defaultParses() {
        JsonArray result = new JsonArray();
        try {
            JsonObject extra = template().getAsJsonObject("EXTRA");
            if (extra != null && extra.has("parses") && extra.get("parses").isJsonArray()) {
                for (JsonElement item : extra.getAsJsonArray("parses")) {
                    if (item.isJsonObject()) {
                        JsonObject copy = item.getAsJsonObject().deepCopy();
                        copy.addProperty("enabled", true);
                        result.add(copy);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    /** 模板：assets/local_source_defaults.json，读一次缓存起来。 */
    public static JsonObject template() {
        if (template != null) return template.deepCopy();
        try (InputStream in = App.get().getAssets().open("local_source_defaults.json")) {
            byte[] bytes = new byte[in.available()];
            int read = in.read(bytes);
            if (read > 0) {
                template = JsonParser.parseString(new String(bytes, 0, read, StandardCharsets.UTF_8)).getAsJsonObject();
                return template.deepCopy();
            }
        } catch (Throwable ignored) {
        }
        template = new JsonObject();
        return template.deepCopy();
    }

    // ------------------------------------------------------------ dir lists

    public static List<String> getDirs() {
        return stringList(settings(), "scan_dirs");
    }

    public static List<String> getEnabledDirs() {
        return stringList(settings(), "scan_enabled_dirs");
    }

    /** 启用的文件夹（规范化去重后），与 MuseTV 的 J() 一致。 */
    public static List<String> enabledDirs(JsonObject json) {
        List<String> dirs = stringList(json, "scan_dirs");
        List<String> enabled = stringList(json, "scan_enabled_dirs");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String dir : dirs) {
            if (!enabled.contains(dir)) continue;
            String path = canonical(dir);
            if (!TextUtils.isEmpty(path)) result.add(path);
        }
        return new ArrayList<>(result);
    }

    public static boolean isDirEnabled(String dir) {
        return getEnabledDirs().contains(normalize(dir));
    }

    public static void setDirEnabled(String dir, boolean enabled) {
        String path = normalize(dir);
        if (TextUtils.isEmpty(path)) return;
        JsonObject json = settings();
        List<String> list = stringList(json, "scan_enabled_dirs");
        if (enabled) {
            if (!list.contains(path)) list.add(path);
        } else {
            list.remove(path);
        }
        putList(json, "scan_enabled_dirs", list);
        save(json);
    }

    public static void addDir(String dir) {
        String path = normalize(dir);
        if (TextUtils.isEmpty(path)) return;
        JsonObject json = settings();
        List<String> dirs = stringList(json, "scan_dirs");
        if (!dirs.contains(path)) dirs.add(path);
        List<String> enabled = stringList(json, "scan_enabled_dirs");
        if (!enabled.contains(path)) enabled.add(path);
        putList(json, "scan_dirs", dirs);
        putList(json, "scan_enabled_dirs", enabled);
        save(json);
    }

    public static void removeDir(String dir) {
        String path = normalize(dir);
        if (TextUtils.isEmpty(path)) return;
        JsonObject json = settings();
        List<String> dirs = stringList(json, "scan_dirs");
        dirs.remove(path);
        List<String> enabled = stringList(json, "scan_enabled_dirs");
        enabled.remove(path);
        putList(json, "scan_dirs", dirs);
        putList(json, "scan_enabled_dirs", enabled);
        save(json);
    }

    /** 设置页那一行的摘要：未启用 / 未设置文件夹 / 扫描结果。 */
    public static String summary() {
        if (!isEnabled()) return App.get().getString(R.string.local_source_disabled);
        List<String> dirs = getDirs();
        if (dirs.isEmpty()) return App.get().getString(R.string.local_source_no_dir);
        String saved = Prefers.getString(KEY_SUMMARY, "");
        if (!TextUtils.isEmpty(saved)) return saved;
        return App.get().getString(R.string.local_source_pending, dirs.size());
    }

    /** 生成文件路径：已保存的 local_source_file，否则第一个扫描目录 + 输出名。 */
    public static String outputPath() {
        String saved = Prefers.getString(KEY_FILE, "");
        return TextUtils.isEmpty(saved) ? outputPath(settings()) : saved;
    }

    public static String outputPath(JsonObject json) {
        List<String> dirs = enabledDirs(json);
        if (dirs.isEmpty()) return "";
        return new File(dirs.get(0), outputName(json)).getAbsolutePath();
    }

    /** 输出文件名：输出名去掉 .json 后缀再加上 .json。 */
    public static String outputName(JsonObject json) {
        String name = string(json, "out_name");
        if (TextUtils.isEmpty(name)) name = "墨本地源";
        name = name.trim();
        if (name.toLowerCase(Locale.US).endsWith(".json")) name = name.substring(0, name.length() - 5);
        return name + ".json";
    }

    /** 输出文件所在目录。 */
    public static String outputDir(JsonObject json) {
        String path = outputPath(json);
        if (TextUtils.isEmpty(path)) return "";
        File parent = new File(path).getAbsoluteFile().getParentFile();
        return parent == null ? "" : parent.getAbsolutePath();
    }

    /**
     * 这是不是 App 自己生成的本地源文件：文件名等于输出名，或是另外两个
     * 同类 App 的生成文件名。用户自己写的配置不在此列，绝不覆盖。
     */
    public static boolean isGeneratedName(JsonObject json, String name) {
        if (TextUtils.isEmpty(name)) return false;
        if (name.equalsIgnoreCase(outputName(json))) return true;
        if (name.equalsIgnoreCase("昏黄本地源.json")) return true;
        String stem = name.toLowerCase(Locale.ROOT).endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        return stem.equalsIgnoreCase("Vesper自动本地源");
    }

    public static String normalize(String dir) {
        if (dir == null) return "";
        String path = dir.trim();
        while (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        return path;
    }

    public static String canonical(String dir) {
        if (TextUtils.isEmpty(dir)) return "";
        File file = new File(dir.trim());
        try {
            return file.getCanonicalPath();
        } catch (Throwable ignored) {
            return file.getAbsolutePath();
        }
    }

    // ------------------------------------------------------------- generate

    /** 后台重新扫描并生成，扫完回主线程调 onDone（与 MuseTV 的 local-source-scan 线程一致）。 */
    public static void scanAsync(Runnable onDone) {
        new Thread(() -> {
            rebuild();
            App.post(() -> {
                if (onDone != null) onDone.run();
            });
        }, "local-source-scan").start();
    }

    /** 后台自动回写：只重写生成文件，不重新加载配置。 */
    public static void refresh() {
        try {
            rebuild();
        } finally {
            refreshScheduled = false;
        }
    }

    /**
     * 重建合并配置并落盘，返回写盘路径（写不下去返回 null）。
     * 与 MuseTV 的 n(true) + g1() 一致。
     */
    public static String rebuild() {
        return rebuild(false);
    }

    public static String rebuild(boolean force) {
        try {
            JsonObject merged = merge();
            return write(merged, force);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 写盘（与 MuseTV 的 g1() 一致）：目标是用户自己写的配置就不写；
     * 超过 8MB 不写；内容没变不重写。
     */
    public static String write(JsonObject merged) {
        return write(merged, false);
    }

    public static String write(JsonObject merged, boolean force) {
        String path = outputPath(settings());
        if (TextUtils.isEmpty(path)) return null;
        if (isUserFile(path)) {
            setBlocked(path);
            return null;
        }
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(merged);
        if (json.length() > MAX_JSON_CHARS) return null;
        synchronized (LocalSource.class) {
            try {
                if (json.equals(lastJson)) return path;
                long now = System.currentTimeMillis();
                if (!force && now - lastWriteAt < 1000) return path;
                lastWriteAt = now;
                lastJson = json;
                File file = new File(path);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) return null;
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                Prefers.put(KEY_FILE, file.getAbsolutePath());
                return file.getAbsolutePath();
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /** 目标文件是不是用户自己写的：已存在、且文件名不是 App 生成的那几个。 */
    private static boolean isUserFile(String path) {
        File file = new File(path);
        if (!file.isFile()) return false;
        return !isGeneratedName(settings(), file.getName());
    }

    private static void setBlocked(String path) {
        blockedPath = path;
    }

    /** 取出最近一次被挡住的写盘路径，读完清掉。 */
    public static String takeBlockedPath() {
        String path = blockedPath;
        blockedPath = "";
        return path;
    }

    // ------------------------------------------------------------------ util

    public static List<String> stringList(JsonObject json, String key) {
        List<String> result = new ArrayList<>();
        JsonElement element = json.get(key);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                try {
                    if (item.isJsonPrimitive()) result.add(item.getAsString());
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    public static void putList(JsonObject json, String key, List<String> list) {
        JsonArray array = new JsonArray();
        for (String item : list) array.add(item);
        json.add(key, array);
    }

    public static String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return (element == null || !element.isJsonPrimitive()) ? "" : element.getAsString();
    }

    public static boolean bool(JsonObject json, String key, boolean def) {
        JsonElement element = json.get(key);
        if (element != null && element.isJsonPrimitive()) {
            try {
                return element.getAsBoolean();
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    public static int maxDepth(JsonObject json) {
        JsonElement element = json.get("max_depth");
        int depth = MAX_DEPTH_DEFAULT;
        if (element != null && element.isJsonPrimitive()) {
            try {
                depth = element.getAsInt();
            } catch (Throwable ignored) {
            }
        }
        return Math.max(0, Math.min(depth, 8));
    }

    /** 去掉名字里的 [分类]，剩下的再 trim。 */
    public static String stripCategory(String name) {
        if (name == null) return "";
        String text = name.replaceAll("\\[[^\\]]*\\]", "");
        return text == null ? "" : text.trim();
    }

    public static String md5(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 扩展名：取文件名最后一个点后面的小写部分。 */
    public static String extension(String name) {
        if (TextUtils.isEmpty(name)) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /** 名字里包含列表中的任一词（不区分大小写）。 */
    public static boolean containsAny(String name, String[] words) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        for (String word : words) {
            if (!TextUtils.isEmpty(word) && lower.contains(word.toLowerCase(Locale.US))) return true;
        }
        return false;
    }

    /** 相对路径：去掉扫描目录前缀（与 MuseTV 的 E0() 一致）。 */
    public static String relativeToScanDir(JsonObject json, String path) {
        if (TextUtils.isEmpty(path)) return path;
        String text = path;
        if (!text.startsWith("/")) {
            try {
                text = android.net.Uri.decode(text);
            } catch (Throwable ignored) {
            }
            for (String dir : enabledDirs(json)) {
                String name = new File(dir).getName();
                if (!TextUtils.isEmpty(name) && text.startsWith(name + "/")) {
                    return text.substring(name.length() + 1);
                }
            }
            return text;
        }
        for (String dir : enabledDirs(json)) {
            String prefix = dir.endsWith("/") ? dir : dir + "/";
            if (text.startsWith(prefix)) return text.substring(prefix.length());
        }
        for (String root : new String[]{"/storage/emulated/0", "/sdcard", "/storage/self/primary"}) {
            String prefix = root + "/";
            if (text.startsWith(prefix)) return text.substring(prefix.length());
        }
        return text;
    }

    /** 相对路径：去掉基准目录前缀（与 MuseTV 的 D0() 一致）。 */
    public static String relativeTo(File file, String base) {
        String path = file.getAbsolutePath();
        if (!TextUtils.isEmpty(base)) {
            String prefix = base.endsWith("/") ? base : base + "/";
            if (path.startsWith(prefix)) return path.substring(prefix.length());
        }
        return path;
    }

    // ----------------------------------------------------------------- scan

    /** 扫描收集器，对应 MuseTV 的 ef：e 站点、f 直播、g 已见路径、h 后缀、i 文件夹名、j 跳过明细。 */
    private static class Collector {
        final List<JsonObject> sites = new ArrayList<>();
        final List<JsonObject> lives = new ArrayList<>();
        final LinkedHashSet<String> seenPaths = new LinkedHashSet<>();
        final LinkedHashSet<String> extensions = new LinkedHashSet<>();
        final LinkedHashSet<String> folders = new LinkedHashSet<>();
        final List<String> skipped = new ArrayList<>();
        int files;
        int hits;
        int skippedCount;
        boolean truncated;
    }

    /** 合并配置（与 MuseTV 的 Q0() 一致），同时写摘要和诊断。 */
    public static JsonObject merge() throws Throwable {
        long started = System.currentTimeMillis();
        JsonObject settings = settings();
        String baseDir = outputDir(settings);
        Collector collector = new Collector();
        for (String dir : enabledDirs(settings)) {
            File root = new File(dir);
            if (!root.isDirectory()) {
                collector.skipped.add(dir + "：目录不存在或不可读");
                continue;
            }
            if (stringList(settings, "blocked_dirs").contains(root.getName())) continue;
            walk(root, 0, false, false, false, false, collector, settings, baseDir);
        }
        JsonObject template = template();
        List<JsonObject> sites = new ArrayList<>();
        addAll(sites, template.get("PINNED_SITES"));
        sites.addAll(collector.sites);
        addAll(sites, template.get("MANUAL_SITES"));
        if (bool(settings, "replace_sites", false)) {
            sites = new ArrayList<>(collector.sites);
        }
        sites = dedupeSites(sites, collector);
        stripMetaKeys(sites);
        int cleaned = cleanupCategories(sites, collector.folders);
        JsonArray lives = new JsonArray();
        String liveUrl = string(settings, "live_url");
        if (!TextUtils.isEmpty(liveUrl)) {
            JsonElement defaults = template.get("DEFAULT_LIVES");
            if (defaults != null && defaults.isJsonArray()) {
                for (JsonElement item : defaults.getAsJsonArray()) {
                    if (item.isJsonObject()) {
                        JsonObject copy = item.getAsJsonObject().deepCopy();
                        copy.addProperty("url", liveUrl);
                        lives.add(copy);
                    }
                }
            }
        }
        for (JsonObject live : collector.lives) lives.add(live);
        JsonObject out = new JsonObject();
        String spider = string(settings, "spider_jar");
        if (TextUtils.isEmpty(spider)) spider = findSpiderJar(settings);
        if (TextUtils.isEmpty(spider)) spider = string(template, "CONFIG_SPIDER");
        if (!TextUtils.isEmpty(spider)) {
            if (spider.startsWith("./") || spider.startsWith("../")) {
                out.addProperty("spider", spider);
            } else {
                out.addProperty("spider", relativeTo(new File(spider), baseDir));
            }
        }
        String logo = findImage(baseDir, "logo");
        if (!TextUtils.isEmpty(logo)) out.addProperty("logo", logo);
        String wallpaper = findImage(baseDir, "wallpaper");
        if (!TextUtils.isEmpty(wallpaper)) out.addProperty("wallpaper", wallpaper);
        out.add("lives", lives);
        JsonArray siteArray = new JsonArray();
        for (JsonObject site : sites) siteArray.add(site);
        out.add("sites", siteArray);
        JsonObject extra = template.has("EXTRA") && template.get("EXTRA").isJsonObject()
                ? template.getAsJsonObject("EXTRA").deepCopy() : new JsonObject();
        JsonArray parses = new JsonArray();
        JsonElement entries = settings.get("parse_entries");
        if (entries == null || !entries.isJsonArray()) entries = defaultParses();
        for (JsonElement item : entries.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            JsonObject entry = item.getAsJsonObject();
            if (!bool(entry, "enabled", true)) continue;
            JsonObject copy = entry.deepCopy();
            copy.remove("enabled");
            parses.add(copy);
        }
        extra.add("parses", parses);
        for (Map.Entry<String, JsonElement> e : extra.entrySet()) out.add(e.getKey(), e.getValue());
        long elapsed = System.currentTimeMillis() - started;
        String summary = App.get().getString(R.string.local_source_summary_format,
                sites.size(), lives.size(), collector.files, collector.hits, collector.skippedCount,
                elapsed, collector.truncated ? App.get().getString(R.string.local_source_truncated) : "");
        Prefers.put(KEY_SUMMARY, summary);
        siteCount = sites.size();
        List<String> exts = new ArrayList<>(collector.extensions);
        String types;
        if (exts.isEmpty()) {
            types = App.get().getString(R.string.local_source_no_types);
        } else if (exts.size() > 12) {
            types = TextUtils.join("、", exts.subList(0, 12)) + App.get().getString(R.string.local_source_more_types, exts.size());
        } else {
            types = TextUtils.join("、", exts);
        }
        List<String> checked = stringList(settings, "scan_extensions");
        String checkedText = checked.isEmpty()
                ? App.get().getString(R.string.local_source_all_types)
                : TextUtils.join("、", checked);
        String diagnosis = App.get().getString(R.string.local_source_diagnosis_format,
                enabledDirs(settings).size(), collector.files, collector.hits, types, checkedText);
        Prefers.put(KEY_DIAGNOSIS, diagnosis);
        if (cleaned > 0) {
            android.util.Log.d("LocalSource", "分类收口：删掉 " + cleaned + " 条源里非文件夹名的分类");
        }
        if (!collector.skipped.isEmpty()) {
            android.util.Log.d("LocalSource", "跳过明细：" + TextUtils.join("；", collector.skipped));
        }
        return out;
    }

    private static void addAll(List<JsonObject> out, JsonElement element) {
        if (element == null || !element.isJsonArray()) return;
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonObject()) out.add(item.getAsJsonObject().deepCopy());
        }
    }

    /** 按 key 去重：同名同大小同 MD5 的折叠成一条，否则 key 改成 key_1、key_2……。 */
    private static List<JsonObject> dedupeSites(List<JsonObject> sites, Collector collector) {
        List<JsonObject> result = new ArrayList<>();
        LinkedHashMap<String, JsonObject> byKey = new LinkedHashMap<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonObject site : sites) {
            JsonObject copy = site.deepCopy();
            String key = string(copy, "key");
            if (TextUtils.isEmpty(key)) {
                result.add(copy);
                names.add(string(copy, "name"));
                continue;
            }
            JsonObject first = byKey.get(key);
            if (first == null) {
                byKey.put(key, copy);
                result.add(copy);
                names.add(string(copy, "name"));
                continue;
            }
            String path = string(copy, "#path");
            String firstPath = string(first, "#path");
            if (!TextUtils.isEmpty(path) && !TextUtils.isEmpty(firstPath)) {
                File a = new File(path);
                File b = new File(firstPath);
                if (a.getName().equals(b.getName()) && a.isFile() && b.isFile()
                        && a.length() == b.length() && md5(a).equals(md5(b))) {
                    android.util.Log.d("LocalSource", "重复源折叠：" + a.getName() + " 与 " + b.getName() + " 同名同内容，只保留一条");
                    continue;
                }
            }
            int n = 1;
            String newKey = key + "_1";
            while (byKey.containsKey(newKey)) {
                n++;
                newKey = key + "_" + n;
            }
            String name = string(copy, "name");
            String base = string(copy, "#base");
            String candidate = name;
            if (!TextUtils.isEmpty(base)) {
                Matcher matcher = CATEGORY_PATTERN.matcher(base);
                List<String> parts = new ArrayList<>();
                while (matcher.find()) {
                    String part = matcher.group(1);
                    if (!TextUtils.isEmpty(part)) parts.add(part.trim());
                }
                if (!parts.isEmpty()) candidate = name + "+" + TextUtils.join("+", parts);
            }
            if (names.contains(candidate)) {
                int m = Math.max(n, 1);
                String numbered = name + m;
                while (names.contains(numbered)) {
                    m++;
                    numbered = name + m;
                }
                candidate = numbered;
            }
            copy.addProperty("key", newKey);
            copy.addProperty("name", candidate);
            byKey.put(newKey, copy);
            result.add(copy);
            names.add(candidate);
        }
        return result;
    }

    /** 删掉站点里 # 开头的内部标记（#path、#base）。 */
    private static void stripMetaKeys(List<JsonObject> sites) {
        for (JsonObject site : sites) {
            List<String> keys = new ArrayList<>();
            for (String key : site.keySet()) {
                if (key.startsWith("#")) keys.add(key);
            }
            for (String key : keys) site.remove(key);
        }
    }

    /** 源名字里的 [分类]，不在文件夹名里的删掉，返回改了几条。 */
    private static int cleanupCategories(List<JsonObject> sites, Set<String> folders) {
        int changed = 0;
        Set<String> lowerFolders = new HashSet<>();
        for (String folder : folders) lowerFolders.add(folder.toLowerCase(Locale.US));
        for (JsonObject site : sites) {
            String name = string(site, "name");
            if (TextUtils.isEmpty(name)) continue;
            Matcher matcher = CATEGORY_PATTERN.matcher(name);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            boolean dirty = false;
            while (matcher.find()) {
                String inner = matcher.group(1);
                if (inner == null) continue;
                String trimmed = inner.trim();
                if (!lowerFolders.contains(trimmed.toLowerCase(Locale.US))) {
                    sb.append(name, last, matcher.start());
                    last = matcher.end();
                    dirty = true;
                }
            }
            if (!dirty) continue;
            sb.append(name.substring(last));
            String cleaned = sb.toString().trim();
            if (!TextUtils.isEmpty(cleaned) && !cleaned.equals(name)) {
                site.addProperty("name", cleaned);
                changed++;
            }
        }
        return changed;
    }

    /** 在启用目录里找蜘蛛 jar：先看设置，再找第一个 .jar。 */
    private static String findSpiderJar(JsonObject settings) {
        for (String dir : enabledDirs(settings)) {
            File[] files = new File(dir).listFiles();
            if (files == null) continue;
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File file : files) {
                if (file.isFile() && "jar".equals(extension(file.getName()))) {
                    return relativeTo(file, outputDir(settings));
                }
            }
        }
        return "";
    }

    /** 找 jar/logo.png 之类的图：jar/logo.gif|jpg|png，返回 ./jar/logo.png 这种相对路径。 */
    private static String findImage(String baseDir, String name) {
        if (TextUtils.isEmpty(baseDir)) return "";
        for (String ext : new String[]{"gif", "jpg", "png"}) {
            File file = new File(baseDir, "jar/" + name + "." + ext);
            if (file.isFile()) return "./jar/" + name + "." + ext;
        }
        return "";
    }

    // ------------------------------------------------------------ walk & parse

    /** 递归扫描一个目录（与 MuseTV 的 e1() 一致）。 */
    private static void walk(File dir, int depth, boolean pq, boolean yq, boolean js, boolean live,
                             Collector out, JsonObject settings, String baseDir) {
        if (depth > maxDepth(settings) || out.truncated) return;
        String dirName = dir.getName();
        String category = dirName == null ? "" : dirName;
        if (!TextUtils.isEmpty(category)) {
            String stripped = stripCategory(category);
            if (!TextUtils.isEmpty(stripped)) {
                category = stripped;
            } else {
                Matcher matcher = CATEGORY_PATTERN.matcher(category);
                if (matcher.find() && !TextUtils.isEmpty(matcher.group(1))) {
                    category = matcher.group(1).trim();
                } else {
                    category = category.trim();
                }
            }
            if (!TextUtils.isEmpty(category)) out.folders.add(category);
        }
        if (!live && LIVE_DIR_NAME.equals(dirName)) live = true;
        if (!pq && containsAny(dirName, DIR_PQ)) pq = true;
        if (!yq && containsAny(dirName, DIR_YQ)) yq = true;
        if (!js && containsAny(dirName, DIR_JS)) js = true;
        if (containsAny(dirName, DIR_NOT_JS)) js = false;
        File[] files = dir.listFiles();
        if (files == null) {
            out.skipped.add(dirName + "：无法读取目录（缺少存储权限？）");
            out.skippedCount++;
            return;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        List<String> blocked = stringList(settings, "blocked_dirs");
        Set<String> allowed = allowedExtensions(settings);
        for (File file : files) {
            if (out.truncated) return;
            if (file.isDirectory()) {
                if (blocked.contains(file.getName())) continue;
                walk(file, depth + 1, pq, yq, js, live, out, settings, baseDir);
            } else if (file.isFile()) {
                processFile(file, category, pq, yq, js, live, out, settings, baseDir, allowed);
            }
        }
    }

    /** 允许扫描的后缀：xbpq/xyq 算 json，wv.js 算 js；没勾选就全都要。 */
    private static Set<String> allowedExtensions(JsonObject settings) {
        List<String> checked = stringList(settings, "scan_extensions");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (checked.isEmpty()) {
            result.addAll(Arrays.asList(DEFAULT_EXTENSIONS));
            return result;
        }
        for (String ext : checked) {
            if (TextUtils.isEmpty(ext)) continue;
            String e = ext.trim().toLowerCase(Locale.US);
            if (e.equals("xbpq") || e.equals("xyq")) result.add("json");
            else if (e.equals("wv.js")) result.add("js");
            else if (Arrays.asList(DEFAULT_EXTENSIONS).contains(e)) result.add(e);
        }
        if (result.isEmpty()) result.addAll(Arrays.asList(DEFAULT_EXTENSIONS));
        return result;
    }

    private static void processFile(File file, String category, boolean pq, boolean yq, boolean js, boolean live,
                                    Collector out, JsonObject settings, String baseDir, Set<String> allowed) {
        String output = outputPath(settings);
        if (!TextUtils.isEmpty(output) && file.getAbsolutePath().equals(new File(output).getAbsolutePath())) return;
        if (isGeneratedName(settings, file.getName())) return;
        if (file.getName().endsWith("_settings.json")) return;
        List<String> excluded = stringList(settings, "excluded_files");
        if (excluded.isEmpty()) excluded = Arrays.asList(EXCLUDED_DEFAULT);
        if (excluded.contains(file.getName())) return;
        String canonical = canonical(file.getAbsolutePath());
        if (!out.seenPaths.add(canonical)) return;
        if (out.files >= MAX_FILES || out.sites.size() >= MAX_SITES) {
            out.truncated = true;
            return;
        }
        out.files++;
        String rawExt = extension(file.getName());
        if (TextUtils.isEmpty(rawExt)) return;
        out.extensions.add(rawExt);
        String ext = rawExt;
        boolean isXbpq = ext.equals("xbpq") || ext.equals("xyq");
        boolean isWvJs = ext.equals("wv.js");
        if (isXbpq) ext = "json";
        if (isWvJs) ext = "js";
        boolean allowedFile;
        if (ext.equals("json")) {
            allowedFile = js || live || allowed.contains("json");
        } else if (ext.equals("py") || ext.equals("js") || ext.equals("php") || ext.equals("html")) {
            allowedFile = allowed.contains(ext);
        } else {
            allowedFile = allowed.contains(ext);
        }
        if (!allowedFile) return;
        String relPath = relativeTo(file, baseDir);
        String stem = file.getName();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        if (ext.equals("json") && (js || live) && bool(settings, "decode_base64", true)) {
            maybeDecodeBase64(file);
        }
        out.hits++;
        if (live) {
            JsonObject entry = liveEntry(displayName(stem, category), relPath, settings);
            out.lives.add(entry);
            if (out.lives.size() >= MAX_LIVES) out.truncated = true;
            return;
        }
        if (js && ext.equals("json")) {
            out.sites.add(spiderSite(displayName(stem, category, rawExt), "csp_XBPQ", relPath, false, 0, file));
            return;
        }
        if (isXbpq) {
            JsonObject site = new JsonObject();
            String name = displayName(stem, category, rawExt);
            site.addProperty("key", name);
            site.addProperty("name", name);
            site.addProperty("type", 0);
            site.addProperty("jar", "./jar/xyqxbpq.jar");
            site.addProperty("api", "csp_XYQHiker");
            site.addProperty("searchable", 0);
            site.addProperty("quickSearch", 0);
            site.addProperty("filterable", 0);
            site.addProperty("changeable", 0);
            site.addProperty("ext", relPath);
            site.addProperty("#path", file.getAbsolutePath());
            site.addProperty("#base", category);
            out.sites.add(site);
            return;
        }
        if (rawExt.equals("js")) {
            String engine = resolveDrpyEngine(baseDir);
            JsonObject site = spiderSite(displayName(stem, category, rawExt), engine, relPath, false, 1, file);
            site.addProperty("order_num", out.sites.size());
            out.sites.add(site);
            return;
        }
        if (rawExt.equals("jar")) {
            JsonObject site = new JsonObject();
            String name = displayName(stem, category, rawExt);
            site.addProperty("key", name);
            site.addProperty("name", name);
            site.addProperty("type", 4);
            site.addProperty("api", "file://" + file.getAbsolutePath());
            site.addProperty("searchable", 0);
            site.addProperty("quickSearch", 0);
            site.addProperty("filterable", 0);
            site.addProperty("changeable", 0);
            site.addProperty("#path", file.getAbsolutePath());
            site.addProperty("#base", category);
            out.sites.add(site);
            return;
        }
        if (isWvJs) {
            JsonObject site = spiderSite(displayName(stem, category, rawExt), "csp_WvSpider", relPath, true, 0, file);
            site.addProperty("jar", "./jar/WvSpider.jar");
            out.sites.add(site);
            return;
        }
        if (ext.equals("json") || ext.equals("txt")) {
            parseConfigFile(file, relPath, category, out);
            return;
        }
        if (ext.equals("m3u")) {
            parseM3u(file, category, out, settings);
            return;
        }
        if (ext.equals("py") || ext.equals("php") || ext.equals("html")) {
            out.sites.add(spiderSite(displayName(stem, category, rawExt), "csp_" + stem, relPath, false, 0, file));
        }
    }

    /** 直播文件夹里的文件：整个文件就是一个直播源。 */
    private static JsonObject liveEntry(String name, String relPath, JsonObject settings) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("type", 0);
        entry.addProperty("url", relPath);
        entry.addProperty("playerType", 1);
        JsonObject first = firstDefaultLive();
        entry.addProperty("epg", first == null ? "" : string(first, "epg"));
        entry.addProperty("logo", first == null ? "" : string(first, "logo"));
        entry.addProperty("ua", first == null ? "" : string(first, "ua"));
        return entry;
    }

    private static JsonObject firstDefaultLive() {
        try {
            JsonElement lives = template().get("DEFAULT_LIVES");
            if (lives != null && lives.isJsonArray() && lives.getAsJsonArray().size() > 0) {
                JsonElement first = lives.getAsJsonArray().get(0);
                if (first.isJsonObject()) return first.getAsJsonObject();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 名字：去掉 [分类]，加上 [文件夹分类]，再加 (后缀)。 */
    private static String displayName(String stem, String category) {
        return displayName(stem, category, "");
    }

    private static String displayName(String stem, String category, String ext) {
        String name = stripCategory(stem == null ? "" : stem);
        if (TextUtils.isEmpty(name)) name = stem == null ? "" : stem;
        if (TextUtils.isEmpty(name)) name = "未命名";
        StringBuilder sb = new StringBuilder(name);
        if (!TextUtils.isEmpty(category)) sb.append("[").append(category.trim()).append("]");
        if (!TextUtils.isEmpty(ext)) sb.append("(").append(ext).append(")");
        return sb.toString();
    }

    /** 通用蜘蛛源（与 MuseTV 的 T0() 一致）。 */
    private static JsonObject spiderSite(String name, String api, String ext, boolean switchable, int changeable, File file) {
        JsonObject site = new JsonObject();
        site.addProperty("key", name);
        site.addProperty("name", name);
        site.addProperty("type", 3);
        site.addProperty("api", api);
        site.addProperty("searchable", 1);
        site.addProperty("quickSearch", 1);
        site.addProperty("filterable", 1);
        if (changeable == 1) site.addProperty("changeable", 1);
        if (switchable) site.addProperty("switchable", 1);
        if (!TextUtils.isEmpty(ext)) site.addProperty("ext", ext);
        site.addProperty("#path", file.getAbsolutePath());
        return site;
    }

    /** drpy 引擎：先找产物目录里的常规位置，再找成套引擎，找不到就用 ./lib/drpy2.min.js。 */
    private static String resolveDrpyEngine(String baseDir) {
        if (TextUtils.isEmpty(baseDir)) return "./lib/drpy2.min.js";
        String[] candidates = {"libs/drpy2.min.js", "libs/lib/drpy2.min.js", "lib/lib/drpy2-fast.min.js",
                "lib/drpy2-fast.min.js", "lib/drpy2.min.js"};
        for (String path : candidates) {
            if (new File(baseDir, path).isFile()) return "./" + path;
        }
        File found = findDrpyCore(new File(baseDir), 0, new int[]{0});
        if (found != null) return "./" + relativeTo(found, baseDir);
        return "./lib/drpy2.min.js";
    }

    private static File findDrpyCore(File dir, int depth, int[] count) {
        if (depth > 3 || count[0] > 2000 || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            count[0]++;
            if (count[0] > 2000) return null;
            if (file.isFile() && "drpy-core-lite.min.js".equalsIgnoreCase(file.getName())) return file;
        }
        for (File file : files) {
            if (file.isDirectory() && !file.getName().startsWith(".")) {
                File found = findDrpyCore(file, depth + 1, count);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** .json 里不是 JSON 就试着 Base64 解码，解出来是 JSON 就直接写回文件。 */
    private static void maybeDecodeBase64(File file) {
        try {
            if (file.length() > MAX_RULE_BYTES) return;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            if (TextUtils.isEmpty(text)) return;
            try {
                JsonParser.parseString(text);
                return;
            } catch (Throwable ignored) {
            }
            byte[] decoded = android.util.Base64.decode(text, android.util.Base64.DEFAULT);
            String decodedText = new String(decoded, StandardCharsets.UTF_8);
            JsonParser.parseString(decodedText);
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(decodedText.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            android.util.Log.d("LocalSource", "Base64 规则已解码：" + file.getName());
        } catch (Throwable ignored) {
        }
    }

    /** .json / .txt 配置文件：里面的 sites / lives 拿出来，记上 #path。 */
    private static void parseConfigFile(File file, String relPath, String category, Collector out) {
        try {
            if (file.length() > MAX_RULE_BYTES) return;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            if (TextUtils.isEmpty(text)) return;
            JsonElement element;
            try {
                element = JsonParser.parseString(text);
            } catch (Throwable e) {
                try {
                    byte[] decoded = android.util.Base64.decode(text, android.util.Base64.DEFAULT);
                    element = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8));
                } catch (Throwable ignored) {
                    return;
                }
            }
            if (!element.isJsonObject()) return;
            JsonObject json = element.getAsJsonObject();
            JsonElement sites = json.get("sites");
            if (sites != null && sites.isJsonArray()) {
                for (JsonElement item : sites.getAsJsonArray()) {
                    if (!item.isJsonObject()) continue;
                    JsonObject site = item.getAsJsonObject().deepCopy();
                    site.addProperty("#path", file.getAbsolutePath());
                    site.addProperty("#base", category);
                    out.sites.add(site);
                }
            }
            JsonElement lives = json.get("lives");
            if (lives != null && lives.isJsonArray()) {
                for (JsonElement item : lives.getAsJsonArray()) {
                    if (item.isJsonObject()) out.lives.add(item.getAsJsonObject().deepCopy());
                }
            }
            if ((sites == null || !sites.isJsonArray() || sites.getAsJsonArray().size() == 0)
                    && (lives == null || !lives.isJsonArray() || lives.getAsJsonArray().size() == 0)
                    && (json.has("key") || (json.has("name") && json.has("api")))) {
                JsonObject site = json.deepCopy();
                site.addProperty("#path", file.getAbsolutePath());
                site.addProperty("#base", category);
                out.sites.add(site);
            }
        } catch (Throwable ignored) {
        }
    }

    /** .m3u：每条 #EXTINF 频道变成一个直播源。 */
    private static void parseM3u(File file, String category, Collector out, JsonObject settings) {
        try {
            if (file.length() > MAX_RULE_BYTES) return;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String name = null;
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#EXTINF")) {
                    int comma = trimmed.lastIndexOf(',');
                    name = comma >= 0 && comma < trimmed.length() - 1 ? trimmed.substring(comma + 1).trim() : null;
                } else if (!trimmed.isEmpty() && !trimmed.startsWith("#") && name != null) {
                    JsonObject entry = liveEntry(displayName(name, category), trimmed, settings);
                    out.lives.add(entry);
                    name = null;
                    if (out.lives.size() >= MAX_LIVES) {
                        out.truncated = true;
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 设置弹窗用的候选文件统计：启用的目录里按后缀筛出来的文件数、后缀集合和前几个文件名。 */
    public static class Candidates {
        public int files;
        public final LinkedHashSet<String> extensions = new LinkedHashSet<>();
        public final List<String> samples = new ArrayList<>();
    }

    public static Candidates scanCandidates() {
        JsonObject settings = settings();
        Candidates result = new Candidates();
        Set<String> allowed = allowedExtensions(settings);
        for (String dir : enabledDirs(settings)) {
            collectCandidates(new File(dir), 0, maxDepth(settings), allowed, result);
            if (result.files >= 3000) break;
        }
        return result;
    }

    private static void collectCandidates(File dir, int depth, int maxDepth, Set<String> allowed, Candidates out) {
        if (depth > maxDepth || out.files >= 3000 || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : files) {
            if (out.files >= 3000) return;
            if (file.isDirectory()) {
                collectCandidates(file, depth + 1, maxDepth, allowed, out);
            } else if (file.isFile()) {
                String ext = extension(file.getName());
                if (TextUtils.isEmpty(ext) || !allowed.contains(ext)) continue;
                out.files++;
                out.extensions.add(ext);
                if (out.samples.size() < 8) out.samples.add(file.getName());
            }
        }
    }

    // --------------------------------------------------------- generate & load

    /** 保存后扫描生成并加载（与 MuseTV 保存后的 local-source-scan / local-source-generate 一致）。 */
    public static void generateAndLoad(android.app.Activity activity, Runnable onDone) {
        new Thread(() -> {
            String path = rebuild(true);
            String blocked = takeBlockedPath();
            App.post(() -> {
                String message;
                if (getSiteCount() == 0) {
                    message = App.get().getString(R.string.local_source_empty, Prefers.getString(KEY_DIAGNOSIS, ""));
                } else {
                    message = App.get().getString(R.string.local_source_done, Prefers.getString(KEY_SUMMARY, ""));
                }
                if (!TextUtils.isEmpty(blocked)) {
                    message += "\n" + App.get().getString(R.string.local_source_write_blocked, blocked);
                }
                com.fongmi.android.tv.utils.Notify.show(message);
                if (onDone != null) onDone.run();
                loadGenerated(activity);
            });
        }, "local-source-generate").start();
    }

    /** 把生成的本地源文件当作点播配置加载；没有生成文件就重新加载当前配置。 */
    public static void loadGenerated(android.app.Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String path = outputPath();
        File file = TextUtils.isEmpty(path) ? null : new File(path);
        com.fongmi.android.tv.bean.Config config = null;
        if (file != null && file.isFile()) {
            String url = "file:/" + path.replace(Path.rootPath(), "");
            config = com.fongmi.android.tv.bean.Config.find(url, 0);
        }
        if (config != null) {
            com.fongmi.android.tv.api.config.VodConfig.load(config, new com.fongmi.android.tv.impl.Callback() {
                @Override
                public void success() {
                }

                @Override
                public void error(String msg) {
                }
            });
        } else {
            com.fongmi.android.tv.api.config.VodConfig.load(
                    com.fongmi.android.tv.bean.Config.vod(),
                    new com.fongmi.android.tv.impl.Callback() {
                        @Override
                        public void success() {
                        }

                        @Override
                        public void error(String msg) {
                        }
                    });
        }
    }
}
