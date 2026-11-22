#!/usr/bin/env bun
/**
 * 把「上一版 app(com.zjr.hesimusic)」的数据转成 v2 可导入的 legacy-export.json。
 *
 * 只做数据搬运,不连设备、不改任何 app:
 *   - db     模式:读 tools/export-legacy-data.ps1 从设备拉回来的 databases/hesimusic_db(+wal)
 *              与 shared_prefs/*.xml;
 *   - tables 模式:读 provider 模式(adb shell content call 分页查询)落下来的 tables/*.json;
 *   - ab     模式:解包 adb backup 生成的 .ab(去头 + inflate + tar),再按 db 模式处理。
 *
 * 产物格式 = 上一版 app 的备份方言 + 只读元信息(见 docs/import-legacy-data.md §3),
 * 因此这份 JSON 既能被 Hesi v2 导入,也能被上一版 app 的「从 JSON 还原」接受。
 *
 * 用法(一般由 export-legacy-data.ps1 调用,不直接手敲):
 *   bun run tools/legacy-export-build.ts --mode db     --work <dir> --out <json> [--source <source.json>]
 *   bun run tools/legacy-export-build.ts --mode tables --work <dir> --out <json> [--source <source.json>]
 *   bun run tools/legacy-export-build.ts --mode ab     --ab <legacy.ab> --work <dir> --out <json>
 */

import { Database } from "bun:sqlite";
import { inflateSync } from "node:zlib";
import {
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { basename, dirname, join } from "node:path";

const FORMAT = "hesi-legacy-export";
const FORMAT_VERSION = 1;
/** 上一版 app 的备份方言版本(BackupRestoreManager 当前为 4)。 */
const BACKUP_VERSION = 4;
/** 允许导出的设置文件(Kotlin 侧 LegacyPreferencePolicy 还会按键过滤)。 */
const PREFERENCE_FILES = ["playback_prefs", "scan_prefs", "sort_prefs"];

type Row = Record<string, any>;

interface Args {
  mode: "db" | "tables" | "ab";
  work: string;
  out: string;
  source?: string;
  ab?: string;
}

interface SourceInfo {
  method?: string;
  serial?: string;
  applicationId?: string;
  versionName?: string;
  versionCode?: number;
  androidRelease?: string;
  deviceModel?: string;
  databaseVersion?: number;
}

function log(message: string): void {
  process.stderr.write("[legacy-export] " + message + "\n");
}

function fail(message: string): never {
  throw new Error(message);
}

function parseArgs(argv: string[]): Args {
  const map = new Map<string, string>();
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const key = arg.slice(2);
    const value = argv[i + 1] && !argv[i + 1].startsWith("--") ? argv[++i] : "true";
    map.set(key, value);
  }
  const mode = map.get("mode");
  if (mode !== "db" && mode !== "tables" && mode !== "ab") {
    fail("--mode 必须是 db / tables / ab");
  }
  const work = map.get("work");
  if (!work) fail("缺少 --work <工作目录>");
  const out = map.get("out") ?? join(work, "legacy-export.json");
  return { mode, work, out, source: map.get("source"), ab: map.get("ab") };
}

// ---------------------------------------------------------------------------
// 数据读取
// ---------------------------------------------------------------------------

interface Sections {
  songs: Row[];
  songLoops: Row[];
  playlists: Row[];
  playlistEntries: Row[];
  favorites: Row[];
  hiddenSongs: Row[];
  smartPlaylists: Row[];
  preferences: Record<string, Row[]>;
  databaseVersion?: number;
}

/**
 * SQLite 打开带 WAL 的库再正常关闭时会把 WAL checkpoint 进主库并删掉 -wal/-shm,
 * 所以构建前先把原始快照复制一份再打开,保证 <work>/db 里留给人工检查的原件不被改动。
 */
function prepareDatabaseCopy(dbDirectory: string): string {
  if (!existsSync(dbDirectory)) fail("找不到数据库目录: " + dbDirectory);
  const buildDirectory = join(dirname(dbDirectory), "db-build");
  rmSync(buildDirectory, { recursive: true, force: true });
  mkdirSync(buildDirectory, { recursive: true });
  for (const name of ["hesimusic_db", "hesimusic_db-wal", "hesimusic_db-shm"]) {
    const source = join(dbDirectory, name);
    if (existsSync(source)) cpSync(source, join(buildDirectory, name));
  }
  log("数据库快照已复制到 " + buildDirectory + "(原件保持不变)");
  return buildDirectory;
}

/** 从拷贝出来的 Room 数据库文件里读(拷贝件,可以读写打开以完成 WAL 恢复)。 */
function readFromDatabase(dbDirectory: string): Sections {
  const dbPath = join(dbDirectory, "hesimusic_db");
  if (!existsSync(dbPath)) fail("找不到数据库文件: " + dbPath);
  const walPath = dbPath + "-wal";
  if (existsSync(walPath) && statSync(walPath).size > 0) {
    log("检测到 WAL(" + statSync(walPath).size + " bytes),打开拷贝件时会自动恢复");
  }
  const db = new Database(dbPath, { readwrite: true });
  try {
    const userVersion = (db.query("PRAGMA user_version").get() as Row | null)?.user_version;
    const query = (sql: string): Row[] => db.query(sql).all() as Row[];
    return {
      songs: query("SELECT * FROM songs ORDER BY id"),
      songLoops: query("SELECT * FROM song_loop ORDER BY songId"),
      playlists: query("SELECT * FROM playlists ORDER BY id"),
      playlistEntries: query('SELECT * FROM playlist_entries ORDER BY playlistId, "order", id'),
      favorites: query("SELECT * FROM favorites ORDER BY filePath, startPosition"),
      hiddenSongs: query("SELECT * FROM hidden_songs ORDER BY filePath, startPosition"),
      smartPlaylists: query("SELECT * FROM smart_playlists ORDER BY id"),
      preferences: {},
      databaseVersion: typeof userVersion === "number" ? userVersion : undefined,
    };
  } finally {
    db.close();
  }
}

/** provider 模式(tables/*.json):每张表一个数组。 */
function readFromTables(tablesDirectory: string): Sections {
  const load = (name: string): Row[] => {
    const path = join(tablesDirectory, name + ".json");
    if (!existsSync(path)) {
      log("缺少表文件 " + path + ",按空表处理");
      return [];
    }
    const parsed = JSON.parse(readFileSync(path, "utf8"));
    if (!Array.isArray(parsed)) fail(path + " 不是数组");
    return parsed as Row[];
  };
  return {
    songs: load("songs"),
    songLoops: load("song_loop"),
    playlists: load("playlists"),
    playlistEntries: load("playlist_entries"),
    favorites: load("favorites"),
    hiddenSongs: load("hidden_songs"),
    smartPlaylists: load("smart_playlists"),
    preferences: {},
  };
}

/** shared_prefs/*.xml → {file: [{key,type,value}]}。 */
function readPreferences(prefsDirectory: string): Record<string, Row[]> {
  if (!existsSync(prefsDirectory)) return {};
  const result: Record<string, Row[]> = {};
  for (const file of readdirSync(prefsDirectory)) {
    if (!file.endsWith(".xml")) continue;
    const name = file.slice(0, -".xml".length);
    if (!PREFERENCE_FILES.includes(name)) continue;
    const xml = readFileSync(join(prefsDirectory, file), "utf8");
    result[name] = parsePreferencesXml(xml);
  }
  return result;
}

function unescapeXml(text: string): string {
  return text
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#x([0-9a-fA-F]+);/g, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec) => String.fromCodePoint(parseInt(dec, 10)))
    .replace(/&amp;/g, "&");
}

/**
 * Android SharedPreferences XML → 备份方言的 [{key,type,value}]。
 * 元素只有 string/int/long/float/boolean/set 六种;set 内部是 <string> 列表。
 */
function parsePreferencesXml(xml: string): Row[] {
  const entries: Row[] = [];
  const pattern = /<(string|int|long|float|boolean|set)\s+name="([^"]*)"(?:\s+value="([^"]*)")?\s*(?:\/>|>([\s\S]*?)<\/\1>)/g;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(xml)) !== null) {
    const [, tag, rawName, rawValue, inner] = match;
    const key = unescapeXml(rawName);
    if (tag === "set") {
      const items: string[] = [];
      const itemPattern = /<string>([\s\S]*?)<\/string>/g;
      let item: RegExpExecArray | null;
      const body = inner ?? "";
      while ((item = itemPattern.exec(body)) !== null) {
        items.push(unescapeXml(item[1]));
      }
      entries.push({ key, type: "string_set", value: items });
      continue;
    }
    const value = rawValue === undefined ? "" : unescapeXml(rawValue);
    switch (tag) {
      case "string":
        entries.push({ key, type: "string", value });
        break;
      case "boolean":
        entries.push({ key, type: "boolean", value: value === "true" });
        break;
      case "int":
        entries.push({ key, type: "int", value: Number.parseInt(value, 10) });
        break;
      case "long":
        entries.push({ key, type: "long", value: Number.parseInt(value, 10) });
        break;
      case "float":
        entries.push({ key, type: "float", value: Number.parseFloat(value) });
        break;
    }
  }
  return entries;
}

// ---------------------------------------------------------------------------
// adb backup(.ab)解包:头部 → zlib → tar
// ---------------------------------------------------------------------------

interface TarEntry {
  name: string;
  data: Buffer;
}

function unpackAndroidBackup(abPath: string): TarEntry[] {
  const raw = readFileSync(abPath);
  // 头部 4 行文本:magic、版本、是否压缩、加密方式
  const headerEnd = findHeaderEnd(raw);
  const headerText = raw.subarray(0, headerEnd).toString("latin1");
  const headerLines = headerText.split("\n");
  const magic = headerLines[0];
  const version = headerLines[1];
  const compressed = headerLines[2];
  const encryption = headerLines[3];
  if (magic !== "ANDROID BACKUP") fail(".ab 文件头不是 ANDROID BACKUP,文件可能损坏");
  if (encryption !== "none") {
    fail(
      "这份 adb backup 是加密的(encryption=" + encryption + ")。" +
        "请在设备弹窗里留空密码重做一次(留空 = 不加密),或改用 -Method runas / provider。",
    );
  }
  let payloadStart = headerEnd;
  if (version !== "1" && version !== "2" && version !== "3" && version !== "4" && version !== "5") {
    log("警告:未知的 adb backup 版本 " + version + ",按兼容模式尝试");
  }
  const payload = raw.subarray(payloadStart);
  const tarBytes = compressed === "1" ? inflateSync(payload) : payload;
  log("backup 版本 " + version + " / 压缩 " + compressed + " / 解包后 " + tarBytes.length + " bytes");
  return parseTar(tarBytes);
}

function findHeaderEnd(buffer: Buffer): number {
  let newlines = 0;
  for (let i = 0; i < Math.min(buffer.length, 512); i++) {
    if (buffer[i] === 0x0a) {
      newlines++;
      if (newlines === 4) return i + 1;
    }
  }
  fail(".ab 文件头不完整(找不到 4 行头部)");
}

function parseTar(bytes: Buffer): TarEntry[] {
  const entries: TarEntry[] = [];
  let offset = 0;
  let pendingLongName: string | null = null;
  while (offset + 512 <= bytes.length) {
    const header = bytes.subarray(offset, offset + 512);
    if (header.every((byte) => byte === 0)) break;
    const rawName = readCString(header, 0, 100);
    const sizeField = readCString(header, 124, 12).trim();
    const size = sizeField.length > 0 ? Number.parseInt(sizeField, 8) : 0;
    const typeFlag = String.fromCharCode(header[156] || 0x30);
    const prefix = readCString(header, 345, 155);
    const dataStart = offset + 512;
    const data = bytes.subarray(dataStart, dataStart + size);
    const name = pendingLongName ?? (prefix.length > 0 ? prefix + "/" + rawName : rawName);
    if (typeFlag === "L") {
      pendingLongName = readCString(data, 0, data.length);
    } else {
      pendingLongName = null;
      if (typeFlag === "0" || typeFlag === "\u0000") entries.push({ name, data: Buffer.from(data) });
    }
    offset = dataStart + Math.ceil(size / 512) * 512;
  }
  return entries;
}

function readCString(buffer: Buffer, offset: number, maxLength: number): string {
  const end = Math.min(buffer.length, offset + maxLength);
  let stop = offset;
  while (stop < end && buffer[stop] !== 0) stop++;
  return buffer.subarray(offset, stop).toString("utf8");
}

/** 把 .ab 里的数据库与设置解到工作目录,后续按 db 模式处理。 */
function extractBackup(abPath: string, work: string, applicationId: string): void {
  const entries = unpackAndroidBackup(abPath);
  const dbDir = join(work, "db");
  const prefsDir = join(work, "prefs");
  mkdirSync(dbDir, { recursive: true });
  mkdirSync(prefsDir, { recursive: true });
  const dbPrefix = "apps/" + applicationId + "/db/";
  const spPrefix = "apps/" + applicationId + "/sp/";
  let dbFiles = 0;
  let prefFiles = 0;
  for (const entry of entries) {
    if (entry.name.startsWith(dbPrefix)) {
      const name = basename(entry.name);
      if (name === "hesimusic_db" || name.startsWith("hesimusic_db-")) {
        writeFileSync(join(dbDir, name), entry.data);
        dbFiles++;
      }
    } else if (entry.name.startsWith(spPrefix) && entry.name.endsWith(".xml")) {
      writeFileSync(join(prefsDir, basename(entry.name)), entry.data);
      prefFiles++;
    }
  }
  log(".ab 解包完成:数据库文件 " + dbFiles + " 个,设置文件 " + prefFiles + " 个");
  if (dbFiles === 0) {
    fail(
      ".ab 里没有 " + applicationId + " 的数据库。请确认 adb backup 时传的是这个包名,且设备上已允许备份。",
    );
  }
}

// ---------------------------------------------------------------------------
// 行 → 备份方言
// ---------------------------------------------------------------------------

function strOrNull(row: Row, key: string): string | null {
  const value = row[key];
  if (value === null || value === undefined) return null;
  const text = String(value);
  return text.length === 0 ? null : text;
}

function num(row: Row, key: string, fallback = 0): number {
  const value = row[key];
  if (value === null || value === undefined) return fallback;
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function bool(row: Row, key: string, fallback = false): boolean {
  const value = row[key];
  if (value === null || value === undefined) return fallback;
  if (typeof value === "boolean") return value;
  if (typeof value === "number") return value !== 0;
  return String(value).toLowerCase() === "true" || String(value) === "1";
}

function toSongJson(row: Row): Row {
  return {
    id: num(row, "id"),
    title: strOrNull(row, "title") ?? "",
    artist: strOrNull(row, "artist") ?? "",
    album: strOrNull(row, "album") ?? "",
    filePath: strOrNull(row, "filePath") ?? "",
    duration: num(row, "duration"),
    trackNumber: num(row, "trackNumber"),
    year: strOrNull(row, "year"),
    genre: strOrNull(row, "genre"),
    mimeType: strOrNull(row, "mimeType") ?? "",
    size: num(row, "size"),
    dateAdded: num(row, "dateAdded"),
    isCue: bool(row, "isCue"),
    cueFilePath: strOrNull(row, "cueFilePath"),
    startPosition: num(row, "startPosition"),
    endPosition: num(row, "endPosition", -1),
    titleInitial: row["titleInitial"] === null || row["titleInitial"] === undefined ? "" : String(row["titleInitial"]),
    folderPath: row["folderPath"] === null || row["folderPath"] === undefined ? "" : String(row["folderPath"]),
  };
}

function toSongLoopJson(row: Row): Row {
  const json: Row = {
    songId: num(row, "songId"),
    loopStartSamples: num(row, "loopStartSamples"),
    loopEndSamples: num(row, "loopEndSamples"),
    sampleRate: num(row, "sampleRate"),
    loopStartMs: num(row, "loopStartMs"),
    loopEndMs: num(row, "loopEndMs"),
    score: num(row, "score"),
    noteDistance: num(row, "noteDistance"),
    loudnessDifference: num(row, "loudnessDifference"),
    createdAt: num(row, "createdAt"),
    confirmed: bool(row, "confirmed", true),
  };
  const candidates = strOrNull(row, "candidatesJson");
  if (candidates !== null) json.candidatesJson = candidates;
  return json;
}

/**
 * 统一排序:runas 走 SQL 排序、provider 走 rowid 分页,顺序天然不同。
 * 导出文件不该因为"用了哪条路径"而字节不同(文档/对比/回归都依赖这一点)。
 */
function sortSections(sections: Sections): Sections {
  const byNumber = (key: string) => (a: Row, b: Row) => num(a, key) - num(b, key);
  return {
    ...sections,
    songs: [...sections.songs].sort(byNumber("id")),
    songLoops: [...sections.songLoops].sort(byNumber("songId")),
    playlists: [...sections.playlists].sort(byNumber("id")),
    playlistEntries: [...sections.playlistEntries].sort((a, b) => {
      const byPlaylist = num(a, "playlistId") - num(b, "playlistId");
      if (byPlaylist !== 0) return byPlaylist;
      const byOrder = num(a, "order") - num(b, "order");
      if (byOrder !== 0) return byOrder;
      return num(a, "id") - num(b, "id");
    }),
    favorites: [...sections.favorites].sort((a, b) => {
      const byPath = String(a.filePath ?? "").localeCompare(String(b.filePath ?? ""));
      if (byPath !== 0) return byPath;
      return num(a, "startPosition") - num(b, "startPosition");
    }),
    hiddenSongs: [...sections.hiddenSongs].sort((a, b) => {
      const byPath = String(a.filePath ?? "").localeCompare(String(b.filePath ?? ""));
      if (byPath !== 0) return byPath;
      return num(a, "startPosition") - num(b, "startPosition");
    }),
    smartPlaylists: [...sections.smartPlaylists].sort(byNumber("id")),
  };
}

function buildBundle(rawSections: Sections, source: SourceInfo): Row {
  const sections = sortSections(rawSections);
  const preferences: Record<string, Row[]> = {};
  for (const [name, entries] of Object.entries(sections.preferences)) {
    if (entries.length > 0) preferences[name] = entries;
  }
  return {
    format: FORMAT,
    formatVersion: FORMAT_VERSION,
    // 与上一版 app 的备份方言保持一致(version 4 = 含 songLoops)。
    version: BACKUP_VERSION,
    exportedAt: Date.now(),
    generator: "tools/export-legacy-data.ps1 (" + (source.method ?? "unknown") + ")",
    source: {
      applicationId: source.applicationId ?? "com.zjr.hesimusic",
      versionName: source.versionName ?? null,
      versionCode: source.versionCode ?? null,
      databaseVersion: sections.databaseVersion ?? source.databaseVersion ?? null,
      deviceSerial: source.serial ?? null,
      deviceModel: source.deviceModel ?? null,
      androidRelease: source.androidRelease ?? null,
      method: source.method ?? null,
    },
    songs: sections.songs.map(toSongJson),
    songLoops: sections.songLoops.map(toSongLoopJson),
    playlists: sections.playlists.map((row) => ({
      id: num(row, "id"),
      name: strOrNull(row, "name") ?? "",
      createdAt: num(row, "createdAt"),
    })),
    playlistEntries: sections.playlistEntries.map((row) => ({
      id: num(row, "id"),
      playlistId: num(row, "playlistId"),
      songId: num(row, "songId"),
      order: num(row, "order"),
    })),
    favorites: sections.favorites.map((row) => ({
      filePath: strOrNull(row, "filePath") ?? "",
      startPosition: num(row, "startPosition"),
      dateAdded: num(row, "dateAdded"),
    })),
    hiddenSongs: sections.hiddenSongs.map((row) => ({
      filePath: strOrNull(row, "filePath") ?? "",
      startPosition: num(row, "startPosition"),
      hiddenAt: num(row, "hiddenAt"),
    })),
    smartPlaylists: sections.smartPlaylists.map((row) => ({
      id: num(row, "id"),
      name: strOrNull(row, "name") ?? "",
      createdAt: num(row, "createdAt"),
      sql: strOrNull(row, "sqlText") ?? strOrNull(row, "sql") ?? "",
    })),
    preferences,
  };
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

function main(): void {
  const args = parseArgs(process.argv.slice(2));
  const sourcePath = args.source ?? join(args.work, "source.json");
  let source: SourceInfo = {};
  if (existsSync(sourcePath)) {
    source = JSON.parse(readFileSync(sourcePath, "utf8")) as SourceInfo;
  } else {
    log("没有 source.json,来源信息留空: " + sourcePath);
  }

  let sections: Sections;
  if (args.mode === "ab") {
    const ab = args.ab ?? join(args.work, "legacy.ab");
    if (!existsSync(ab)) fail("找不到 .ab 文件: " + ab);
    extractBackup(ab, args.work, source.applicationId ?? "com.zjr.hesimusic");
    sections = readFromDatabase(prepareDatabaseCopy(join(args.work, "db")));
  } else if (args.mode === "tables") {
    sections = readFromTables(join(args.work, "tables"));
  } else {
    sections = readFromDatabase(prepareDatabaseCopy(join(args.work, "db")));
  }
  sections.preferences = readPreferences(join(args.work, "prefs"));

  const bundle = buildBundle(sections, source);
  const targetDir = dirname(args.out);
  if (targetDir && !existsSync(targetDir)) mkdirSync(targetDir, { recursive: true });
  const text = JSON.stringify(bundle);
  writeFileSync(args.out, text, "utf8");

  const summary = {
    out: args.out,
    bytes: Buffer.byteLength(text, "utf8"),
    mode: args.mode,
    databaseVersion: sections.databaseVersion ?? null,
    songs: sections.songs.length,
    songLoops: sections.songLoops.length,
    playlists: sections.playlists.length,
    playlistEntries: sections.playlistEntries.length,
    favorites: sections.favorites.length,
    hiddenSongs: sections.hiddenSongs.length,
    smartPlaylists: sections.smartPlaylists.length,
    preferenceFiles: Object.keys(sections.preferences).length,
    preferenceEntries: Object.values(sections.preferences).reduce((sum, items) => sum + items.length, 0),
  };
  // stdout 只输出这一行 JSON,供 PowerShell 解析展示;进度信息走 stderr。
  process.stdout.write(JSON.stringify(summary) + "\n");
}

try {
  main();
} catch (error) {
  const message = error instanceof Error ? error.message : String(error);
  process.stderr.write("[legacy-export] 失败: " + message + "\n");
  process.exit(1);
}
