# 阶段三：UI 交互与列表视图

## 目标
完成主页面的 UI 开发，包括歌曲、歌手、专辑、文件夹四个 Tab 的展示与交互。

## 任务列表

### 1. 首页框架
- [ ] 实现 `MainScreen` 使用 `Scaffold`。
- [ ] 实现顶部 `TabRow` 和 `HorizontalPager` (4个页面)。
- [ ] 实现底部 `BottomPlayerBar` (悬浮播放条)。

### 2. 列表数据源 (Dao & Repository)
- [ ] 优化 `SongDao` 查询:
    - `getSongs()`
    - `getArtists()`
    - `getAlbums()`
    - `getFolders()`
- [ ] 实现 `LibraryRepository` 提供各 Tab 的数据流。

### 3. 列表 UI 组件
- [ ] 实现 `MusicListItem` (通用列表项)。
- [ ] 实现 `SongList` (歌曲 Tab):
    - 支持首字母索引 (Sticky Header 或 侧边栏)。
    - 支持点击播放。
- [ ] 实现 `ArtistList` / `AlbumList` / `FolderList`:
    - 显示副标题 (歌曲数量)。
    - 点击跳转到详情页。

### 4. 详情页 (动态列表)
- [ ] 实现 `SongListScreen` (通用歌曲列表页)。
- [ ] 支持传入参数 (Type: Artist/Album/Folder, Value: ID/Path)。
- [ ] 根据参数查询并展示对应的歌曲列表。

## 验收标准
1.  首页四个 Tab 能流畅滑动切换。
2.  歌曲列表按首字母排序，且能通过索引快速跳转。
3.  点击 "周杰伦" 能进入歌手详情页，只显示周杰伦的歌。
4.  点击 "文件夹" 能按层级浏览文件。
5.  底部播放条常驻，且能实时更新当前歌曲信息。
