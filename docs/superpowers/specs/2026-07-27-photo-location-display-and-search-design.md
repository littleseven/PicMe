# 图片地理位置（端侧离线逆地理 + 展示 + 按位置检索）设计

> **状态**：设计稿，待写实现计划
> **日期**：2026-07-27
> **目标**：让相册图片在城市/区级别可靠地展示拍摄地点，并把「位置」做成相册内的一级检索维度。覆盖无 Google 服务（GMS）国内机型上系统 `Geocoder` 返回空、导致「有经纬度却没有地名」的核心缺口。

## 1. 背景与现状

仓库里位置相关基础设施**已大量存在**，本设计是增量完善，不是从零搭建：

| 能力 | 现状 | 位置 |
|------|------|------|
| EXIF GPS 读取 | `ExifInterface.latLong` 读经纬度 | `MetadataExtractor.kt:87-103` |
| 逆地理编码 | **仅** Android `Geocoder.getFromLocation`（依赖 GMS，无 GMS 机型返回空） | `MetadataExtractor.kt:108-123` |
| 扁平存储 | `MediaEntity.latitude/longitude/locationName` | `MediaEntity.kt:38-40` |
| 层级存储 | `LocationHierarchyEntity`（country/province/city/district/poi）+ `MediaLocationEntity` 关联表 | `data/local/entity/` |
| 层级索引写入 | `LocationIndexUpdater`（坐标 4 位去重，~11m） | `LocationIndexUpdater.kt` |
| 单图展示 | `PhotoInfoDialog` 一行「位置: xxx」；缺名时退化成裸「GPS: 39.9075, 116.3972」 | `MediaPager.kt:1409-1413` |
| 按位置检索（DAO） | `searchByPlace(query)` 已 join 层级表，匹配 city/district/poi/province | `LocationDao.kt:23-35` |
| 按地点分组 | 相册「按地点」分组 + `gallery_group_location` | strings / Gallery |
| 字段映射到 UI | `MediaRepositoryImpl:440-442` 把 lat/locationName 灌进 runtime-core `MediaAsset` | — |

**核心缺口**：
1. **覆盖**：截图/下载/被分享图无 EXIF GPS——天生没有，不在本设计范围（无 GPS 即不展示位置行）。
2. **Geocoder 不可靠**（命门）：无 GMS 的国内机型（如国行 HyperOS）`Geocoder` 返回空 → 有经纬度也无「城市」。
3. **展示弱**：埋在弹窗一行纯文本；已有的省/市/区/POI 层级没用上；裸经纬度直接暴露给用户。
4. **检索未显式露出**：`searchByPlace` 在 DAO 层已可用，但相册 UI 没有「按城市·地区」的显式筛选维度。

## 2. 目标 / 非目标

**目标**
- 无 GMS 机型也能拿到城市/区级地名（端侧离线逆地理）。
- 单图详情页规范展示拍摄地点（省/市/区/POI），可点击跳用户自装地图 App，不再暴露裸经纬度。
- 相册内「位置」成为一级检索维度：搜索框可命中（已有，回填后生效）+ 显式「地点」筛选入口。
- 全程端侧、零网络，契合隐私优先红线。

**非目标**
- 不引入地图 SDK、不做「照片地图」视图（后续可扩展）。
- 不解决「无 GPS 图片」的位置（截图/下载图无位置是固有的）。
- 离线精度不到 POI/街道级（仅省/市/区；POI/街道在有 GMS 机型上由系统 `Geocoder` 提供）。

## 3. 设计

### 3.1 端侧离线地名核心（新增）

**数据资产** `app/src/main/assets/geo/admin_centroids_zh.json`
- 省 + 市 + 区县质心表，约 2800 条，每条 `{province, city, district, lat, lon}`（中文名）。
- 来源：开放数据。首选 GeoNames（admin1/admin2 + cities500 for CN，**CC-BY 4.0，需署名**），或民政部行政区划码 + 经纬度。
- 打包体积约几百 KB ~ 1-2MB。
- 署名：在「设置/关于」或隐私说明中注明数据来源与许可。

**`AdminCentroidIndex`**（纯 Kotlin，`app/data/indexing/geo/`）
- 装载上述 JSON 为 `List<Centroid(province, city, district, lat, lon)>`。
- `fun nearest(lat: Double, lon: Double): Centroid?` —— **暴力最近邻**遍历 ~2800 点、Haversine 距离。微秒级，**不引入 KD-tree**（YAGNI；点数小，暴力更简单可测）。
- 纯函数、无 Android 依赖，便于 JVM 单测。

**`OfflineGeocoder`**（`app/data/indexing/geo/`）
- 持有 `AdminCentroidIndex`；`fun lookup(lat: Double, lon: Double): ResolvedLocation?` 把最近质心映射为 `ResolvedLocation`。

**`ResolvedLocation`**（小 data class，`app/data/indexing/geo/`）
- `data class ResolvedLocation(country, province, city, district, poi, lat, lon)`（均 nullable）。
- 统一结构：系统 `Geocoder` 结果与离线结果**都**产出它，下游不再依赖 `android.location.Address`。

### 3.2 逆地理编码接入（改造）

**`MetadataExtractor.reverseGeocode()`**
- 改为返回 `ResolvedLocation?`：
  1. 先调系统 `Geocoder`（现有；有 GMS 给街道/POI）→ `Address` 转 `ResolvedLocation`。
  2. **为空 → `OfflineGeocoder.lookup()` 兜底**。
- `ExtractionResult` 由「只带 flat `locationName`」升级为带 `resolved: ResolvedLocation?`。
- `locationName` 仍写一份**规范层级串**（见 3.4），供子串检索与简单展示。

**`LocationIndexUpdater.updateIndex()`**
- 入参由 `address: Address?` 改为 `resolved: ResolvedLocation?`（与 Geocoder 解耦，离线路径也能写省/市/区/POI）。
- 写 `location_hierarchy` + `media_locations` 的去重/关联逻辑不变。

### 3.3 存量回填

- 选中 `latitude != null && locationName == null` 的存量媒体（历史上 `Geocoder` 失败、有坐标无名）。
- 跑离线兜底 → 重写 `locationName` + 层级表。
- 挂在 `MediaIndexingWorker` 作为一个**有界 pass**（跑完即止，不常驻）。

### 3.4 单图展示（`PhotoInfoDialog`）

**简化决策（相对初稿）**：展示**不**给 runtime-core `MediaAsset` 加字段、**不**改 `MediaRepositoryImpl` 映射、**不**新增列表期 join（避免 N+1）。改为：

- `locationName` 作为唯一展示来源，写入时保证是规范层级串：
  - 离线路径：`listOfNotNull(province, city, district, poi).joinToString(" ")`，如 `"北京 海淀区 中关村"`。
  - `Geocoder` 路径：沿用 `locality + subLocality` 或回退 `getAddressLine(0)`，并尽量对齐成「省 市 区」形态。
- `PhotoInfoDialog`（`MediaPager.kt:1409` 段）位置行重做：
  - 主文本 = `locationName`（规范层级串）。
  - **整行可点** → 启动 `geo:` intent：`Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")`，交给用户自装地图 App（高德/百度/Google Maps）。零 SDK、零网络、用户主动行为。
  - **不再暴露裸经纬度**给用户（移除当前 `GPS: %.4f, %.4f` 分支）。
  - 无 GPS（`locationName == null && lat == null`）：**隐藏该行**（不显示「未知位置」噪声，沿用现状）。
  - 经纬度若需保留：放进可折叠「详细」子区，默认不展示（可选，v1 可不做）。
- 经纬度仍取自 `MediaAsset.latitude/longitude`（已有），无需新字段。

### 3.5 相册按位置检索

- **搜索框（零改动，回填后生效）**：`LocationDao.searchByPlace(query)` 已 join 层级表匹配 city/district/poi/province。回填后输入「北京」「海淀」即可命中。
- **新增「地点」筛选维度（显式入口）**：
  - 新增 DAO 查询 `getCityCounts()`：按 city 聚合 join `media_locations` 的媒体数。
    ```sql
    SELECT l.city AS city, l.province AS province, COUNT(ml.mediaId) AS count
    FROM location_hierarchy l
    INNER JOIN media_locations ml ON l.locationId = ml.locationId
    WHERE l.city IS NOT NULL
    GROUP BY l.city, l.province
    ORDER BY count DESC
    ```
    返回 `List<CityCount(city, province, count)>`（新 POJO）。
  - 相册顶部/筛选区新增「地点」入口：列出用户拍过照的城市及数量，点击即按该城市过滤网格（复用 `searchByPlace(city)` 或新增按 city 精确过滤）。
  - 复用现有「按地点」分组语义与 `location_hierarchy` 的 `city/province` 索引，不新建大组件。

## 4. 数据模型与文件改动清单

**新增**
- `app/src/main/assets/geo/admin_centroids_zh.json`（数据资产 + 生成说明）。
- `app/.../data/indexing/geo/AdminCentroidIndex.kt`（纯函数最近邻）。
- `app/.../data/indexing/geo/OfflineGeocoder.kt`。
- `app/.../data/indexing/geo/ResolvedLocation.kt`。
- `app/.../data/local/dao`：`getCityCounts(): List<CityCount>` + `CityCount` POJO（可放 `data/local/entity` 或 `model`）。
- 「地点」筛选 UI 组件（gallery feature，小）。

**改造**
- `MetadataExtractor.kt`：`reverseGeocode` 返回 `ResolvedLocation?`；`ExtractionResult` 带 `resolved`；离线兜底。
- `LocationIndexUpdater.kt`：入参 `Address?` → `ResolvedLocation?`。
- `MediaIndexingWorker.kt`：新增有界「位置回填」pass；调用方相应调整。
- `MediaPager.kt`：`PhotoInfoDialog` 位置行（规范串 + `geo:` intent + 隐藏裸经纬度）。
- 相册筛选区：「地点」入口接线。

**i18n**
- 新增字符串（位置行 label、地点筛选标题等）同步 `values/`、`values-zh-rCN/`、`values-zh-rTW/`。
- 隐私说明文案补「地理位置在本地解析」。

## 5. 数据流

```
新扫描图片：
  ExifInterface.latLong → (lat, lon)
    → Geocoder.getFromLocation → Address? → ResolvedLocation?
    → 为空 → OfflineGeocoder.lookup(lat, lon) → ResolvedLocation?
    → 写 MediaEntity.{latitude, longitude, locationName=层级串}
    → 写 location_hierarchy + media_locations（去重）
    → searchByPlace / getCityCounts 可命中

存量回填（有界 pass）：
  SELECT * WHERE latitude!=null AND locationName==null
    → OfflineGeocoder.lookup → 重写 locationName + 层级表

展示：
  PhotoInfoDialog 打开 → 显示 locationName（层级串）
    → 点击 → geo:lat,lon intent → 用户自装地图 App

检索：
  搜索框输入 → searchByPlace(query)（已存在，回填后生效）
  「地点」入口 → getCityCounts() → 列城市+数量 → 点选 → 过滤网格
```

## 6. 错误处理

- 离线资产缺失/损坏 → 记日志、降级为「无名」（经纬度仍入库，`geo:` intent 仍可用）。
- `Geocoder` 抛 `IOException` → 已 catch，走离线兜底。
- 无 GPS → 无位置行、不进「地点」筛选项。
- `OfflineGeocoder.lookup` 全空（资产未加载）→ 返回 null，等同无地名。

## 7. 测试（JVM 单测）

- `AdminCentroidIndexTest`：已知坐标 → 预期城市（北京 39.90,116.40 → 北京市；上海 31.23,121.47 → 上海市；跨市边界点归到最近者）。
- `Address → ResolvedLocation` 与离线 `Centroid → ResolvedLocation` 映射测试。
- `MetadataExtractor` 兜底链路：`Geocoder` 空 → 离线命中；两者皆空 → null。
- `locationName` 层级串格式化纯函数（有/无 POI/无 GPS 三态）。
- 回填选择逻辑（`latitude!=null && locationName==null`）纯函数。
- `getCityCounts` 查询：Room 测试（Robolectric 内存库）造数据 → 聚合正确。
- `PhotoInfoDialog` 位置行：有 locationName 显示+可点；无 GPS 隐藏（Compose UI 测试或纯逻辑提取测试）。

## 8. 隐私

- 逆地理全程端侧（系统 `Geocoder` 本地 + 离线库），**零网络**。
- `geo:` intent 是用户主动点击后跳转外部 App，由用户选择。
- 与项目 `[PRIVACY]` 红线一致：不上传坐标到任何服务器。
- 隐私说明文案补充「媒体拍摄地点在本地解析」。

## 9. 开放问题

- 离线数据源最终选定 GeoNames 还是民政部区划码（许可与体积权衡）——实现期定，spec 默认 GeoNames（CC-BY 4.0）。
- 「地点」筛选入口的具体落位（顶部 chip 行 / 筛选面板）——实现期按现有相册筛选 UI 风格对齐。
- 是否保留可折叠「经纬度」详细子区——v1 默认不展示，按反馈再加。
