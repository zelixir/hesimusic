// 把「日常版」用户数据(歌单/收藏/隐藏/自建 SQL 歌单)同步到「Dev 版」数据库。
// 为什么需要:两个包 applicationId 不同 → 各自独立 DB、独立扫描 → 歌曲 id 完全不同,
// 因此不能直接拷 playlist_entries.songId,必须按 filePath(+CUE startPosition)重新映射。
//
// 用法(设备上两个包都要先 force-stop):
//   adb exec-out run-as com.zjr.hesimusic     cat databases/hesimusic_db > daily_db
//   adb exec-out run-as com.zjr.hesimusic.dev cat databases/hesimusic_db > dev_db
//   bun run tools/dev-sync-playlists.ts daily_db dev_db
//   adb push dev_db /data/local/tmp/dev_db
//   adb shell "run-as com.zjr.hesimusic.dev sh -c 'rm -f databases/hesimusic_db-wal databases/hesimusic_db-shm; cat /data/local/tmp/dev_db > databases/hesimusic_db'"
//   adb shell am start -n com.zjr.hesimusic.dev/com.zjr.hesimusic.MainActivity
//
// 幂等:每次先清空 Dev 的 playlists/playlist_entries 再从日常版重建;收藏/隐藏按主键 REPLACE;
// SQL 歌单按 name 去重(不会重复插入内置的「无缝循环」)。
// 结束时会 wal_checkpoint(TRUNCATE) + journal_mode=DELETE + integrity_check,产出自包含单文件 DB。
import { Database } from "bun:sqlite";

const [dailyPath, devPath] = [process.argv[2], process.argv[3]];
const daily = new Database(dailyPath, { readonly: true });
const dev = new Database(devPath);

const devSongs = dev.query("SELECT id, filePath, startPosition FROM songs").all() as { id: number; filePath: string; startPosition: number }[];
const byPathStart = new Map<string, number>();
const byPath = new Map<string, number>();
for (const s of devSongs) {
  byPathStart.set(s.filePath + "|" + s.startPosition, s.id);
  if (!byPath.has(s.filePath)) byPath.set(s.filePath, s.id);
}
console.log("dev songs =", devSongs.length);

const dailySongs = daily.query("SELECT id, filePath, startPosition FROM songs").all() as { id: number; filePath: string; startPosition: number }[];
const idMap = new Map<number, number>();
let mapped = 0, skipped = 0;
for (const s of dailySongs) {
  const hit = byPathStart.get(s.filePath + "|" + s.startPosition) ?? byPath.get(s.filePath);
  if (hit != null) { idMap.set(s.id, hit); mapped++; } else skipped++;
}
console.log("song id map:", "mapped=" + mapped, "skipped=" + skipped);

const playlists = daily.query("SELECT id, name, createdAt FROM playlists ORDER BY id").all() as any[];
const entries = daily.query("SELECT playlistId, songId, \"order\" o FROM playlist_entries ORDER BY playlistId, \"order\"").all() as any[];
const favorites = daily.query("SELECT filePath, startPosition, dateAdded FROM favorites").all() as any[];
const hidden = daily.query("SELECT filePath, startPosition, hiddenAt FROM hidden_songs").all() as any[];
const smart = daily.query("SELECT name, createdAt, sqlText FROM smart_playlists").all() as any[];

const tx = dev.transaction(() => {
  dev.exec("DELETE FROM playlist_entries");
  dev.exec("DELETE FROM playlists");
  const insertPlaylist = dev.prepare("INSERT INTO playlists (name, createdAt) VALUES (?, ?)");
  const insertEntry = dev.prepare("INSERT INTO playlist_entries (playlistId, songId, \"order\") VALUES (?, ?, ?)");
  const newIdByOld = new Map<number, number>();
  for (const p of playlists) {
    const r = insertPlaylist.run(p.name, p.createdAt);
    newIdByOld.set(p.id, Number(r.lastInsertRowid));
  }
  let eIns = 0, eSkip = 0;
  for (const e of entries) {
    const newPlaylist = newIdByOld.get(e.playlistId);
    const newSong = idMap.get(e.songId);
    if (newPlaylist == null || newSong == null) { eSkip++; continue; }
    insertEntry.run(newPlaylist, newSong, e.o);
    eIns++;
  }
  const insertFav = dev.prepare("INSERT OR REPLACE INTO favorites (filePath, startPosition, dateAdded) VALUES (?, ?, ?)");
  for (const f of favorites) insertFav.run(f.filePath, f.startPosition, f.dateAdded);
  const insertHidden = dev.prepare("INSERT OR REPLACE INTO hidden_songs (filePath, startPosition, hiddenAt) VALUES (?, ?, ?)");
  for (const h of hidden) insertHidden.run(h.filePath, h.startPosition, h.hiddenAt);
  const existingSmart = new Set((dev.query("SELECT name FROM smart_playlists").all() as any[]).map(r => r.name));
  const insertSmart = dev.prepare("INSERT INTO smart_playlists (name, createdAt, sqlText) VALUES (?, ?, ?)");
  let sIns = 0;
  for (const s of smart) { if (!existingSmart.has(s.name)) { insertSmart.run(s.name, s.createdAt, s.sqlText); sIns++; } }
  return { playlists: playlists.length, entries: eIns, entriesSkipped: eSkip, favorites: favorites.length, hidden: hidden.length, smartInserted: sIns };
});

const res = tx();
console.log("copied:", JSON.stringify(res));
console.log("checkpoint:", JSON.stringify(dev.query("PRAGMA wal_checkpoint(TRUNCATE)").all()));
dev.exec("PRAGMA journal_mode=DELETE");
console.log("integrity:", JSON.stringify(dev.query("PRAGMA integrity_check").all()));
console.log("verify playlists:", JSON.stringify(dev.query("SELECT p.id, p.name, COUNT(e.id) c FROM playlists p LEFT JOIN playlist_entries e ON e.playlistId = p.id GROUP BY p.id ORDER BY p.id").all()));
console.log("verify smart:", JSON.stringify(dev.query("SELECT id, name FROM smart_playlists ORDER BY id").all()));
console.log("favorites:", JSON.stringify(dev.query("SELECT COUNT(*) c FROM favorites").get()));
console.log("hidden:", JSON.stringify(dev.query("SELECT COUNT(*) c FROM hidden_songs").get()));
dev.close();
daily.close();