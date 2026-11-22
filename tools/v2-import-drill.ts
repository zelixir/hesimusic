#!/usr/bin/env bun
/**
 * Hesi v2 导入演练(真机):导出上一版数据 → 安装 .v2 → 触发导入 → 断言 → 截图/日志 → 卸载 .v2。
 *
 * 用法:
 *   bun run tools/v2-import-drill.ts --apk app/build/outputs/apk/v2/debug/app-v2-debug.apk
 *   bun run tools/v2-import-drill.ts --apk <apk> --method backup --serial b1e84dad
 *   bun run tools/v2-import-drill.ts --apk <apk> --export backups/drill/runas/legacy-export.json --keep-v2
 *
 * 设计要点:
 *   - 只读上一版(com.zjr.hesimusic):演练前后各查一次计数,断言完全一致;
 *   - v2(com.zjr.hesimusic.v2)是独立 applicationId,卸载它不影响上一版;
 *   - 演练把导出文件拷进 v2 自己的 filesDir(用 run-as),这样导入页用 file:// 直接读,
 *     无需给 v2 授予"所有文件访问权限";真实用户走 SAF 选目录(见 docs/import-legacy-data.md);
 *   - 断言全部走 v2 自带的 adb 数据库接口(content://com.zjr.hesimusic.v2.adbdb),
 *     与上一版的查询口径一致;
 *   - 结束默认卸载 v2(测试机保持现状),证据写进工作目录。
 *
 * 退出码 0 = 全部断言通过。
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const LEGACY_APP = "com.zjr.hesimusic";
const V2_APP = "com.zjr.hesimusic.v2";
const V2_IMPORT_ACTIVITY = V2_APP + "/com.zjr.hesimusic.v2.legacyimport.LegacyImportActivity";
const REMOTE_BUNDLE = "files/drill-export.json";
const REMOTE_BUNDLE_PATH = "/data/user/0/" + V2_APP + "/" + REMOTE_BUNDLE;

/** 与 LegacyPreferencePolicy 保持一致(设置白名单的镜像,只用于预计数)。 */
const PREFERENCE_FILES = new Set(["playback_prefs", "scan_prefs", "sort_prefs"]);
const BLOCKED_KEYS = new Set([
  "queue_ids", "current_song_index", "current_song_id", "current_position",
  "playlist_type", "playlist_value", "state_version",
]);
const URI_KEYS = new Set(["startup_image_uri", "list_background_image_uri"]);

interface DrillArgs {
  apk: string;
  method: "runas" | "backup" | "provider";
  serial?: string;
  exportJson?: string;
  workDir: string;
  keepV2: boolean;
  skipInstall: boolean;
  importTimeoutSeconds: number;
}

interface CmdResult {
  code: number;
  out: string;
  err: string;
}

function log(message: string): void {
  process.stdout.write(message + "\n");
}

function fatal(message: string): never {
  process.stderr.write("[drill] 失败: " + message + "\n");
  process.exit(1);
}

function parseArgs(argv: string[]): DrillArgs {
  const map = new Map<string, string>();
  const flags = new Set<string>();
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith("--")) {
      map.set(key, next);
      i++;
    } else {
      flags.add(key);
    }
  }
  const apk = map.get("apk");
  if (!apk) fatal("缺少 --apk <v2 debug apk 路径>");
  if (!existsSync(apk)) fatal("APK 不存在: " + apk);
  const method = (map.get("method") ?? "runas") as DrillArgs["method"];
  if (!["runas", "backup", "provider"].includes(method)) fatal("--method 必须是 runas/backup/provider");
  const stamp = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
  return {
    apk,
    method,
    serial: map.get("serial"),
    exportJson: map.get("export"),
    workDir: map.get("work") ?? join("backups", "drill-" + stamp),
    keepV2: flags.has("keep-v2"),
    skipInstall: flags.has("skip-install"),
    importTimeoutSeconds: Number.parseInt(map.get("timeout") ?? "300", 10),
  };
}

function resolveAdb(): string {
  const fromEnv = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  const candidates = [
    fromEnv ? join(fromEnv, "platform-tools", "adb.exe") : "",
    fromEnv ? join(fromEnv, "platform-tools", "adb") : "",
    process.env.LOCALAPPDATA ? join(process.env.LOCALAPPDATA, "Android", "Sdk", "platform-tools", "adb.exe") : "",
  ].filter((item) => item.length > 0);
  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate;
  }
  return "adb";
}

const args = parseArgs(process.argv.slice(2));
const adb = resolveAdb();
const target = args.serial ? ["-s", args.serial] : [];

function run(command: string, commandArgs: string[]): CmdResult {
  const result = Bun.spawnSync([command, ...commandArgs], { stdout: "pipe", stderr: "pipe", env: { ...process.env } });
  return {
    code: result.exitCode ?? -1,
    out: result.stdout.toString("utf8"),
    err: result.stderr.toString("utf8"),
  };
}

function adbCmd(commandArgs: string[]): CmdResult {
  return run(adb, [...target, ...commandArgs]);
}

function adbShell(command: string): CmdResult {
  return adbCmd(["shell", command]);
}

/** 解析 content call 的输出:Result: Bundle[{result={...}, ok=true}] */
function parseContentCall(raw: string): any {
  const index = raw.indexOf("result=");
  if (index < 0) fatal("content call 输出里找不到 result=: " + raw.slice(0, 300));
  const rest = raw.slice(index + 7).trim();
  for (let end = rest.length - 1; end > 0; end--) {
    if (rest[end] !== "}") continue;
    try {
      return JSON.parse(rest.slice(0, end + 1));
    } catch {
      // 继续往前缩
    }
  }
  throw new Error("无法解析 content call 的 JSON");
}

function providerQuery(appId: string, sql: string): any[] {
  const command = "content call --uri content://" + appId + ".adbdb --method query --arg '" + sql.replace(/'/g, "'\\''") + "'";
  const result = adbShell(command);
  if (result.code !== 0) fatal("content call 失败(" + appId + "): " + result.err);
  const parsed = parseContentCall(result.out);
  return Array.isArray(parsed.rows) ? parsed.rows : [];
}

function providerCount(appId: string, table: string): number {
  return Number(providerQuery(appId, "SELECT COUNT(*) AS n FROM " + table)[0]?.n ?? 0);
}

/**
 * 分页取全表:上一版的 adbdb 接口单次最多返回 500 行(SELECT * FROM (...) LIMIT 500),
 * 直接查 1746 首会被截断——断言会因此误判(踩过:歌曲只看到 500 首,歌单条目全部映射失败)。
 * 统一按主键翻页,并用别名 _k 带回翻页键。
 */
function fetchAll(appId: string, select: string, table: string, key: string): any[] {
  const rows: any[] = [];
  let last = 0;
  let guard = 0;
  while (guard++ < 200) {
    const page = providerQuery(
      appId,
      "SELECT " + key + " AS _k, " + select + " FROM " + table + " WHERE " + key + " > " + last + " ORDER BY " + key,
    );
    if (page.length === 0) break;
    rows.push(...page);
    last = Number(page[page.length - 1]._k);
    if (page.length < 500) break;
  }
  return rows;
}

function hasPackage(packageName: string): boolean {
  return adbShell("pm path " + packageName).out.includes("package:");
}

function sleep(ms: number): void {
  Bun.sleepSync(ms);
}

function takeScreenshot(path: string): boolean {
  const result = Bun.spawnSync([adb, ...target, "exec-out", "screencap", "-p"], { stdout: "pipe", stderr: "pipe" });
  if ((result.exitCode ?? -1) !== 0) return false;
  writeFileSync(path, new Uint8Array(result.stdout));
  return true;
}

/**
 * 点掉 ColorOS/OPPO 的"安装向导"确认页(adb install 会卡在这一步)。
 * 按钮文案:继续安装 / 安装 / Install / Continue;取消在左边,必须精确匹配避免点错。
 */
function tapInstallGuide(): boolean {
  adbShell("uiautomator dump /sdcard/drill-install-ui.xml");
  const xml = adbShell("cat /sdcard/drill-install-ui.xml").out;
  const nodes = [...xml.matchAll(/<node\b[^>]*>/g)].map((match) => match[0]);
  const buttons: { text: string; x: number; y: number }[] = [];
  for (const node of nodes) {
    if (!node.includes('class="android.widget.Button"') || !node.includes('clickable="true"')) continue;
    const text = /text="([^"]*)"/.exec(node)?.[1] ?? "";
    const bounds = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(node);
    if (!bounds) continue;
    buttons.push({
      text,
      x: Math.round((Number(bounds[1]) + Number(bounds[3])) / 2),
      y: Math.round((Number(bounds[2]) + Number(bounds[4])) / 2),
    });
  }
  const exact = buttons.find((button) => /^(继续安装|安装|继续|确定|安装应用|Install|Continue)$/.test(button.text.trim()));
  const loose = buttons.find((button) => /继续安装|安装/.test(button.text) && !/取消|Cancel/.test(button.text));
  const target = exact ?? loose;
  if (!target) return false;
  adbShell("input tap " + target.x + " " + target.y);
  log("  已点掉安装向导: " + (target.text.trim() || "右侧按钮"));
  return true;
}

async function installApkWithAutoConfirm(apk: string): Promise<{ code: number; out: string; err: string }> {
  const process = Bun.spawn([adb, ...target, "install", "-r", "-t", apk], { stdout: "pipe", stderr: "pipe" });
  const deadline = Date.now() + 240000;
  while (process.exitCode === null && Date.now() < deadline) {
    const top = adbShell("dumpsys activity activities").out;
    if (/InstallGuideActivity|PackageInstaller|com\.oplus\.appdetail/.test(top)) {
      tapInstallGuide();
    }
    await Bun.sleep(1000);
  }
  if (process.exitCode === null) {
    process.kill();
    return { code: -1, out: "", err: "安装超时(可能卡在系统安装向导)" };
  }
  return {
    code: process.exitCode,
    out: await new Response(process.stdout).text(),
    err: await new Response(process.stderr).text(),
  };
}

function expectedSettingsEntries(bundle: any): number {
  let count = 0;
  for (const [fileName, entries] of Object.entries(bundle.preferences ?? {})) {
    const name = String(fileName).replace(/\.xml$/, "");
    if (!PREFERENCE_FILES.has(name)) continue;
    for (const entry of entries as any[]) {
      if (BLOCKED_KEYS.has(entry.key)) continue;
      if (URI_KEYS.has(entry.key)) {
        if (typeof entry.value !== "string") continue;
        if (!entry.value.startsWith("/") && !entry.value.startsWith("file://")) continue;
      }
      count++;
    }
  }
  return count;
}

// ---------------------------------------------------------------------------

const checks: { name: string; ok: boolean; detail: string }[] = [];
function check(name: string, ok: boolean, detail: string): void {
  checks.push({ name, ok, detail });
  log((ok ? "  [PASS] " : "  [FAIL] ") + name + " —— " + detail);
}

mkdirSync(args.workDir, { recursive: true });
log("==== Hesi v2 导入演练 ====");
log("  工作目录: " + args.workDir);
log("  APK: " + args.apk);
log("  导出方法: " + args.method);

const state = adbCmd(["get-state"]);
if (!state.out.includes("device")) fatal("没有可用设备: " + state.out + state.err);
const serial = adbShell("getprop ro.serialno").out.trim();
const model = adbShell("getprop ro.product.model").out.trim();
const release = adbShell("getprop ro.build.version.release").out.trim();
log("  设备: " + model + " / Android " + release + " / " + serial);

if (!hasPackage(LEGACY_APP)) fatal("设备上没有上一版 " + LEGACY_APP);
const legacyVersion = adbShell("dumpsys package " + LEGACY_APP).out.match(/versionName=(\S+)/)?.[1] ?? "unknown";
log("  上一版: " + legacyVersion);

const legacyBaseline = {
  songs: providerCount(LEGACY_APP, "songs"),
  songLoop: providerCount(LEGACY_APP, "song_loop"),
  playlists: providerCount(LEGACY_APP, "playlists"),
  playlistEntries: providerCount(LEGACY_APP, "playlist_entries"),
  favorites: providerCount(LEGACY_APP, "favorites"),
  hiddenSongs: providerCount(LEGACY_APP, "hidden_songs"),
};
log("  上一版基线: " + JSON.stringify(legacyBaseline));

// ---- 1) 导出 ----
let exportJsonPath = args.exportJson ?? "";
if (exportJsonPath === "") {
  log("\n[1/8] 导出上一版数据(" + args.method + ")");
  const exportWork = join(args.workDir, "export");
  const psArgs = [
    "-NoProfile", "-File", join("tools", "export-legacy-data.ps1"),
    "-Method", args.method,
    "-WorkDir", exportWork,
    "-ApplicationId", LEGACY_APP,
    "-SkipPush",
    "-AutoConfirm",
    "-BackupTimeoutSeconds", "300",
  ];
  if (args.serial) psArgs.push("-Serial", args.serial);
  const exported = run("pwsh", psArgs);
  if (exported.code !== 0) fatal("导出失败:\n" + exported.out + exported.err);
  exportJsonPath = join(exportWork, "legacy-export.json");
  if (!existsSync(exportJsonPath)) fatal("导出脚本没有产出 " + exportJsonPath);
} else {
  log("\n[1/8] 复用已有导出: " + exportJsonPath);
}
const bundle = JSON.parse(readFileSync(exportJsonPath, "utf8"));
log("  导出内容: 歌曲 " + bundle.songs.length + " / 循环 " + bundle.songLoops.length +
  " / 歌单 " + bundle.playlists.length + "(" + bundle.playlistEntries.length + " 条) / 收藏 " + bundle.favorites.length +
  " / 隐藏 " + bundle.hiddenSongs.length + " / SQL 歌单 " + bundle.smartPlaylists.length);

// ---- 2) 安装 v2 ----
log("\n[2/8] 安装 " + V2_APP + "(部分 ROM 会弹系统安装向导,脚本自动点「继续安装」)");
if (!args.skipInstall) {
  const install = await installApkWithAutoConfirm(args.apk);
  if (install.code !== 0 || !hasPackage(V2_APP)) fatal("安装失败:\n" + install.out + install.err);
} else if (!hasPackage(V2_APP)) {
  fatal("指定了 --skip-install 但设备上没有 " + V2_APP);
}
const v2Version = adbShell("dumpsys package " + V2_APP).out.match(/versionName=(\S+)/)?.[1] ?? "unknown";
log("  v2: " + v2Version);
// 清掉 v2 的历史数据,保证每次演练的起点都是空库(只动 v2,不碰上一版)。
// pm clear 在部分 ROM 上会被"应用正在运行"挡住,所以再直接删一次库(debug 包可 run-as)。
adbShell("am force-stop " + V2_APP);
const cleared = adbShell("pm clear " + V2_APP);
log("  pm clear " + V2_APP + ": " + cleared.out.trim());
adbShell("run-as " + V2_APP + " sh -c 'rm -rf databases files/import-backup-*.db files/drill-export.json shared_prefs'");
const baselineSongs = providerCount(V2_APP, "songs");
check("v2 起点是空库", baselineSongs === 0, "songs=" + baselineSongs);
check("两个包并存(上一版 + v2)", hasPackage(LEGACY_APP) && hasPackage(V2_APP), LEGACY_APP + " + " + V2_APP);

// ---- 3) 把导出文件放进 v2 私有目录 ----
log("\n[3/8] 准备导入文件(v2 私有目录,无需授予存储权限)");
const pushTmp = adbCmd(["push", exportJsonPath, "/data/local/tmp/drill-export.json"]);
if (pushTmp.code !== 0) fatal("adb push 失败: " + pushTmp.err);
// 注意:刚安装完 files/ 目录可能还不存在,run-as 的 cwd 只是包数据目录,先 mkdir -p。
const copyCommand = "cat /data/local/tmp/drill-export.json | run-as " + V2_APP + " sh -c 'mkdir -p files && cat > " + REMOTE_BUNDLE + "'";
const copy = adbShell(copyCommand);
if (copy.code !== 0) fatal("写入 v2 私有目录失败: " + copy.err);
const remoteList = adbShell("run-as " + V2_APP + " ls -l " + REMOTE_BUNDLE);
log("  " + remoteList.out.trim());
adbShell("rm -f /data/local/tmp/drill-export.json");
check("导出文件已就位于 v2 私有目录", remoteList.out.includes("drill-export.json"), remoteList.out.trim());

// ---- 4) 触发导入 ----
log("\n[4/8] 启动导入页并自动导入");
adbCmd(["logcat", "-c"]);
// -S:强制先 kill 应用再启动。否则第二次 am start 会命中已经在栈顶的同一个 Activity
// (同样的 data/extras),系统只把它带到前台、不重建,导致"第二次导入"根本没发生(踩过)。
const startCommand = "am start -S -n " + V2_IMPORT_ACTIVITY + " -d file://" + REMOTE_BUNDLE_PATH + " --ez autoImport true";
const started = adbShell(startCommand);
log("  " + started.out.trim().split("\n").join(" "));

const expectedLoopCount = bundle.songLoops.length;
const deadline = Date.now() + args.importTimeoutSeconds * 1000;
let imported = false;
let importLog = "";
while (Date.now() < deadline) {
  sleep(2000);
  importLog = adbCmd(["logcat", "-d", "-s", "LegacyDataImporter:I", "LegacyImport:I", "*:S"]).out;
  if (importLog.includes("导入完成") || importLog.includes("导入计划为空")) {
    imported = true;
    break;
  }
  if (importLog.includes("导入失败")) break;
  if (providerCount(V2_APP, "song_loop") >= expectedLoopCount) {
    imported = true;
    break;
  }
}
writeFileSync(join(args.workDir, "logcat-import.txt"), importLog);
const doneLine = importLog.split("\n").find((line) => line.includes("导入完成")) ?? "";
const noopLine = importLog.split("\n").find((line) => line.includes("导入计划为空")) ?? "";
check("导入已执行", imported, (doneLine || noopLine || "见 logcat-import.txt").trim());
if (noopLine !== "") {
  fatal("首次导入被判为无操作(基线已是最新),演练前提不成立");
}
const settingsApplied = Number(/settings=(\d+)/.exec(doneLine)?.[1] ?? "-1");
const elapsedMatch = /用时 (\d+)ms/.exec(doneLine);
log("  导入日志: " + doneLine.trim());

// ---- 5) 断言 ----
log("\n[5/8] 断言(v2 vs 上一版导出)");
const v2Songs = fetchAll(V2_APP, "id, title, filePath, startPosition", "songs", "id");
const v2Loops = fetchAll(V2_APP, "songId, loopStartSamples, loopEndSamples, confirmed", "song_loop", "songId");
const v2Playlists = fetchAll(V2_APP, "id, name, createdAt", "playlists", "id");
const v2Entries = fetchAll(V2_APP, "id, playlistId, songId, \"order\"", "playlist_entries", "id");
const v2Favorites = fetchAll(V2_APP, "filePath, startPosition", "favorites", "rowid");
const v2Hidden = fetchAll(V2_APP, "filePath, startPosition", "hidden_songs", "rowid");
const v2Smart = fetchAll(V2_APP, "id, name", "smart_playlists", "id");

const keyOf = (path: string, start: number): string => path + "|" + start;
const legacySongKeyById = new Map<number, string>();
for (const song of bundle.songs) legacySongKeyById.set(Number(song.id), keyOf(song.filePath, Number(song.startPosition)));
const v2SongKeyById = new Map<number, string>();
for (const song of v2Songs) v2SongKeyById.set(Number(song.id), keyOf(song.filePath, Number(song.startPosition)));

const legacySongKeys = new Set(bundle.songs.map((song: any) => keyOf(song.filePath, Number(song.startPosition))));
const v2SongKeys = new Set(v2Songs.map((song: any) => keyOf(song.filePath, Number(song.startPosition))));
const missingSongs = [...legacySongKeys].filter((key) => !v2SongKeys.has(key));
check("歌曲数一致", v2Songs.length === bundle.songs.length, "v2=" + v2Songs.length + " 导出=" + bundle.songs.length);
check("歌曲按(路径, CUE 起点)全部就位", missingSongs.length === 0,
  missingSongs.length === 0 ? "无缺失" : "缺失 " + missingSongs.length + " 首,例如 " + missingSongs[0]);

const expectedLoopPoints = new Map<string, string>();
for (const loop of bundle.songLoops) {
  const key = legacySongKeyById.get(Number(loop.songId));
  if (!key) continue;
  expectedLoopPoints.set(key, Number(loop.loopStartSamples) + ":" + Number(loop.loopEndSamples));
}
const actualLoopPoints = new Map<string, string>();
for (const loop of v2Loops) {
  const key = v2SongKeyById.get(Number(loop.songId));
  if (!key) continue;
  actualLoopPoints.set(key, Number(loop.loopStartSamples) + ":" + Number(loop.loopEndSamples));
}
const wrongLoops = [...expectedLoopPoints.entries()].filter(([key, value]) => actualLoopPoints.get(key) !== value);
check("循环记录数一致", v2Loops.length === bundle.songLoops.length, "v2=" + v2Loops.length + " 导出=" + bundle.songLoops.length);
check("循环记录挂对歌曲且循环点一致", wrongLoops.length === 0,
  wrongLoops.length === 0 ? "全部一致" : "不一致 " + wrongLoops.length + " 条,例如 " + JSON.stringify(wrongLoops[0]));

const legacyPlaylistById = new Map<number, any>();
for (const playlist of bundle.playlists) legacyPlaylistById.set(Number(playlist.id), playlist);
const legacySongById = new Map<number, any>();
for (const song of bundle.songs) legacySongById.set(Number(song.id), song);
const legacyOrderByPlaylist = new Map<string, string[]>();
for (const entry of [...bundle.playlistEntries].sort((a: any, b: any) => Number(a.order) - Number(b.order))) {
  const playlist = legacyPlaylistById.get(Number(entry.playlistId));
  const song = legacySongById.get(Number(entry.songId));
  if (!playlist || !song) continue;
  const list = legacyOrderByPlaylist.get(playlist.name) ?? [];
  list.push(keyOf(song.filePath, Number(song.startPosition)));
  legacyOrderByPlaylist.set(playlist.name, list);
}
const v2PlaylistNameById = new Map<number, string>();
for (const playlist of v2Playlists) v2PlaylistNameById.set(Number(playlist.id), playlist.name);
const v2OrderByPlaylist = new Map<string, string[]>();
for (const entry of [...v2Entries].sort((a: any, b: any) => Number(a.order) - Number(b.order))) {
  const name = v2PlaylistNameById.get(Number(entry.playlistId));
  const songKey = v2SongKeyById.get(Number(entry.songId));
  if (!name || !songKey) continue;
  const list = v2OrderByPlaylist.get(name) ?? [];
  list.push(songKey);
  v2OrderByPlaylist.set(name, list);
}
const legacyNames = [...legacyOrderByPlaylist.keys()].sort();
const v2Names = [...v2OrderByPlaylist.keys()].sort();
check("歌单名一致", JSON.stringify(legacyNames) === JSON.stringify(v2Names),
  "v2=" + JSON.stringify(v2Names) + " 导出=" + JSON.stringify(legacyNames));
const orderProblems: string[] = [];
for (const name of legacyNames) {
  const expected = legacyOrderByPlaylist.get(name) ?? [];
  const actual = v2OrderByPlaylist.get(name) ?? [];
  if (JSON.stringify(expected) !== JSON.stringify(actual)) {
    orderProblems.push(name + " 期望 " + expected.length + " 首 / 实得 " + actual.length + " 首");
  }
}
check("歌单内歌曲顺序一致", orderProblems.length === 0,
  orderProblems.length === 0 ? "全部一致(" + legacyNames.join("、") + ")" : orderProblems.join("; "));

const legacyFavoriteKeys = new Set(bundle.favorites.map((item: any) => keyOf(item.filePath, Number(item.startPosition))));
const v2FavoriteKeys = new Set(v2Favorites.map((item: any) => keyOf(item.filePath, Number(item.startPosition))));
check("收藏一致", v2Favorites.length === bundle.favorites.length &&
  [...legacyFavoriteKeys].every((key) => v2FavoriteKeys.has(key)),
  "v2=" + v2Favorites.length + " 导出=" + bundle.favorites.length);
const legacyHiddenKeys = new Set(bundle.hiddenSongs.map((item: any) => keyOf(item.filePath, Number(item.startPosition))));
const v2HiddenKeys = new Set(v2Hidden.map((item: any) => keyOf(item.filePath, Number(item.startPosition))));
check("隐藏一致", v2Hidden.length === bundle.hiddenSongs.length &&
  [...legacyHiddenKeys].every((key) => v2HiddenKeys.has(key)),
  "v2=" + v2Hidden.length + " 导出=" + bundle.hiddenSongs.length);
const smartNames = new Set(v2Smart.map((item: any) => item.name));
const expectedSmart = bundle.smartPlaylists.map((item: any) => item.name);
check("SQL 歌单导入(重名的内置项跳过)", expectedSmart.every((name: string) => smartNames.has(name)),
  "v2=" + JSON.stringify([...smartNames]) + " 导出=" + JSON.stringify(expectedSmart));

const expectedSettings = expectedSettingsEntries(bundle);
check("设置写入项数符合白名单", settingsApplied === expectedSettings,
  "实际写入 " + settingsApplied + " 项,预期 " + expectedSettings + " 项(播放状态键/跨应用 URI 被拦截)");
const elapsed = elapsedMatch ? Number(elapsedMatch[1]) : -1;
// 注意:不要把 "a >= 0 && a < b" 直接写成 check(...) 的实参 —— Bun 的 TS 解析器会把它
// 当成泛型实参列表而报 "Invalid assignment target",先取成局部变量。
const elapsedWithinBudget = elapsed >= 0 && elapsed < 120000;
check("导入耗时在合理范围(小于 120 秒)", elapsedWithinBudget, elapsed >= 0 ? elapsed + " ms" : "日志里没有耗时");

// 导入前备份
const backupLogLine = importLog.split("\n").find((line) => line.includes("导入前备份完成")) ?? "";
check("导入前自动备份了 v2 数据库", backupLogLine !== "", backupLogLine.trim() || "日志里没有备份行");

// ---- 5.9) 首次导入的界面证据(截图 + UI 树)----
log("\n[5.9/8] 记录首次导入结果页");
const firstShot = join(args.workDir, "v2-import-first-result.png");
check("首次导入结果页已截图", takeScreenshot(firstShot), firstShot);
adbShell("uiautomator dump /sdcard/drill-ui-first.xml");
const firstUi = adbShell("cat /sdcard/drill-ui-first.xml").out;
writeFileSync(join(args.workDir, "v2-import-first-ui.xml"), firstUi);
const firstTexts = [...firstUi.matchAll(/text="([^"]+)"/g)].map((match) => match[1]).filter((text) => text.trim().length > 0);
writeFileSync(join(args.workDir, "v2-import-first-ui-texts.txt"), firstTexts.join("\n"));
const resultLine = firstTexts.find((text) => text.includes("导入完成") || text.includes("用时")) ?? "";
log("  结果页文本: " + firstTexts.filter((text) => /歌曲:|循环记录:|歌单:|收藏:|设置|用时|备份/.test(text)).slice(0, 8).join(" | "));

// ---- 6) 幂等 ----
log("\n[6/8] 幂等复测(第二次导入应判为无操作)");
adbCmd(["logcat", "-c"]);
const beforeSecond = {
  songs: providerCount(V2_APP, "songs"),
  songLoop: providerCount(V2_APP, "song_loop"),
  playlistEntries: providerCount(V2_APP, "playlist_entries"),
  favorites: providerCount(V2_APP, "favorites"),
};
adbShell(startCommand);
let secondLog = "";
let sawNoop = false;
const secondDeadline = Date.now() + 120000;
while (Date.now() < secondDeadline) {
  sleep(2000);
  secondLog = adbCmd(["logcat", "-d", "-s", "LegacyDataImporter:I", "LegacyImport:I", "*:S"]).out;
  if (secondLog.includes("导入计划为空")) {
    sawNoop = true;
    break;
  }
  if (secondLog.includes("导入完成")) break;
}
writeFileSync(join(args.workDir, "logcat-second-import.txt"), secondLog);
const afterSecond = {
  songs: providerCount(V2_APP, "songs"),
  songLoop: providerCount(V2_APP, "song_loop"),
  playlistEntries: providerCount(V2_APP, "playlist_entries"),
  favorites: providerCount(V2_APP, "favorites"),
};
check("第二次导入不改数据(幂等)", JSON.stringify(beforeSecond) === JSON.stringify(afterSecond),
  JSON.stringify(beforeSecond) + " -> " + JSON.stringify(afterSecond));
check("第二次导入被判为无操作", sawNoop, sawNoop ? "日志: 导入计划为空" : "未看到无操作日志");

// ---- 7) 上一版未被改动 ----
log("\n[7/8] 确认上一版数据未被改动");
const legacyAfter = {
  songs: providerCount(LEGACY_APP, "songs"),
  songLoop: providerCount(LEGACY_APP, "song_loop"),
  playlists: providerCount(LEGACY_APP, "playlists"),
  playlistEntries: providerCount(LEGACY_APP, "playlist_entries"),
  favorites: providerCount(LEGACY_APP, "favorites"),
  hiddenSongs: providerCount(LEGACY_APP, "hidden_songs"),
};
check("上一版数据完全未变", JSON.stringify(legacyBaseline) === JSON.stringify(legacyAfter),
  JSON.stringify(legacyBaseline) + " -> " + JSON.stringify(legacyAfter));

// ---- 8) 证据 + 卸载 ----
log("\n[8/8] 证据与收尾");
const screenshotPath = join(args.workDir, "v2-import-second-result.png");
check("已截图(第二次导入结果页)", takeScreenshot(screenshotPath), screenshotPath);
adbShell("uiautomator dump /sdcard/drill-ui.xml");
const uiXml = adbShell("cat /sdcard/drill-ui.xml").out;
writeFileSync(join(args.workDir, "v2-import-ui.xml"), uiXml);
const uiTexts = [...uiXml.matchAll(/text="([^"]+)"/g)].map((match) => match[1]).filter((text) => text.trim().length > 0);
writeFileSync(join(args.workDir, "v2-import-ui-texts.txt"), uiTexts.join("\n"));
log("  界面文本: " + uiTexts.slice(0, 14).join(" | "));

if (!args.keepV2) {
  const uninstall = adbCmd(["uninstall", V2_APP]);
  check("演练后卸载 v2(测试机保持现状)", !hasPackage(V2_APP), uninstall.out.trim() || uninstall.err.trim());
} else {
  log("  按 --keep-v2 保留 v2 安装");
}

const failed = checks.filter((item) => !item.ok);
const report = [
  "# Hesi v2 导入演练报告",
  "",
  "- 时间: " + new Date().toISOString(),
  "- 设备: " + model + " / Android " + release + " / " + serial,
  "- 上一版: " + LEGACY_APP + " " + legacyVersion,
  "- v2: " + V2_APP + " " + v2Version,
  "- APK: " + args.apk,
  "- 导出方法: " + args.method,
  "- 导出文件: " + exportJsonPath,
  "- 导出内容: 歌曲 " + bundle.songs.length + " / 循环 " + bundle.songLoops.length +
    " / 歌单 " + bundle.playlists.length + "(" + bundle.playlistEntries.length + " 条) / 收藏 " +
    bundle.favorites.length + " / 隐藏 " + bundle.hiddenSongs.length + " / SQL 歌单 " + bundle.smartPlaylists.length,
  "",
  "## 断言",
  "",
  "| 结果 | 断言 | 细节 |",
  "|---|---|---|",
  ...checks.map((item) => "| " + (item.ok ? "PASS" : "FAIL") + " | " + item.name + " | " +
    item.detail.replace(/\|/g, "\\|").replace(/\n/g, " ") + " |"),
  "",
  failed.length === 0 ? "**结论:全部通过**" : "**结论:失败 " + failed.length + " 项**",
  "",
  "## 证据文件",
  "",
  "- logcat-import.txt:首次导入日志(含 导入完成 与备份路径)",
  "- logcat-second-import.txt:第二次导入日志(无操作)",
  "- v2-import-first-result.png / v2-import-first-ui-texts.txt:首次导入结果页截图与文案",
  "- v2-import-second-result.png / v2-import-ui-texts.txt:第二次(幂等)结果页截图与文案",
  "- export/legacy-export.json:本次导入的导出文件",
  "",
].join("\n");
writeFileSync(join(args.workDir, "drill-report.md"), report);
log("\n报告: " + join(args.workDir, "drill-report.md"));
log(failed.length === 0 ? "\n==== 演练通过 ====" : "\n==== 演练失败 " + failed.length + " 项 ====");
process.exit(failed.length === 0 ? 0 : 1);
