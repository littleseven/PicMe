# 图片地理位置（端侧离线逆地理 + 展示 + 按位置检索）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让无 GMS 国内机型也能拿到城市/区级地名，相册单图规范展示拍摄地点并可跳地图，同时把「位置」做成相册内的一级浏览维度（按地点分组 + 搜索框命中）。

**Architecture:** 新增纯 Kotlin 离线地名核心（`AdminCentroidIndex` 暴力最近邻 + `OfflineGeocoder` + `ResolvedLocation`），挂在 `MetadataExtractor` 作为系统 `Geocoder` 失败时的兜底；逆地理产出统一 `ResolvedLocation`，喂 `LocationIndexUpdater`（补全省/市/区，当前这些列恒为 null）和 `MediaEntity.city`。展示复用 `locationName`（规范层级串）+ `geo:` intent；检索复用已接线的 `searchByPlace` + 新增 `GroupingMode.LOCATION` 按城市分组。全程端侧零网络。

**Tech Stack:** Kotlin, Room（migration 15→16）, Moshi 未用此处（用 `org.json`，main+test 均可用）, JUnit4 + Robolectric(4.14.1, sdk=28) 单测, Compose UI, Android `Geocoder`/`ExifInterface`。

**Spec:** `docs/superpowers/specs/2026-07-27-photo-location-display-and-search-design.md`

**关键实现期决策（相对 spec §3.4 / §3.5 的细化，不改变可见行为）：**
- 「地点」检索维度落地为 **`GroupingMode.LOCATION`（按城市分组）**，不做独立筛选面板——复用既有分组管线（`DATE/FACE/PERSON` 同构）+ 已存在的 `gallery_group_location` 文案；自由文本检索已由 `searchByPlace`（`QueryBuilder.kt:146` / `MediaSearchEngine.kt:350,882`）接线，回填后即生效。
- 为分组引入 **`MediaEntity.city`（与 `locationName` 同样去范式化）**，避免在仓库层做 N+1 层级 join；层级表 `location_hierarchy` 仍独立喂 `searchByPlace`。
- 离线库 v1 落到 **地级市/州质心**（~340 行，GeoNames 开放数据），足够「城市等」展示与按城市分组；区县级（~2800）作为后续数据增强，索引代码不变。

---

## File Structure

**新增（`app/src/main/java/com/mamba/picme/data/indexing/geo/`）**
- `ResolvedLocation.kt` — 逆地理统一结果 + `toDisplayString()` 规范层级串。
- `Centroid.kt` — 离线库一行（省/市/区 + 坐标）。
- `AdminCentroidIndex.kt` — 纯函数暴力最近邻。
- `OfflineGeocoder.kt` — `lookup()` + `fromAssets()` + `parseCentroids()`。

**新增资产/脚本**
- `app/src/main/assets/geo/admin_centroids_zh.json` — 种子数据（Task 4），Task 5 用脚本替换为全国。
- `scripts/gen_admin_centroids.py` — GeoNames → JSON 生成器。

**新增测试（`app/src/test/java/com/mamba/picme/data/indexing/geo/`）**
- `ResolvedLocationTest.kt`、`AdminCentroidIndexTest.kt`、`OfflineGeocoderTest.kt`、`MetadataExtractorMappingTest.kt`、`LocationIndexUpdaterTest.kt`、`BackfillSelectorTest.kt`、`GetGroupedMediaLocationTest.kt`、`MediaDaoCityTest.kt`。

**改造**
- `MetadataExtractor.kt` — `reverseGeocode` 返回 `ResolvedLocation`，Geocoder 失败走离线兜底；`ExtractionResult` 带 `resolved`。
- `LocationIndexUpdater.kt` — 入参 `Address?` → `ResolvedLocation?`。
- `MediaIndexingWorker.kt` — 调用点改传 `resolved`；新增有界回填 pass。
- `data/model/MediaEntity.kt` — 加 `city`。
- `runtime-core/.../model/context/MediaAsset.kt` — 加 `city`。
- `data/local/AppDatabase.kt` — version 15→16 + `MIGRATION_15_16`。
- `data/local/MediaDao.kt` — `updateIndexResult` 加 `city`；加回填查询。
- `data/repository/MediaRepositoryImpl.kt` — `toDomain` 映射 `city`。
- `features/gallery/components/MediaPager.kt` — `PhotoInfoDialog` 位置行（`geo:` intent，隐藏裸经纬度）。
- `domain/model/MediaGrouping.kt` — `GroupingMode.LOCATION` + `GroupTitleType.LOCATION/NO_LOCATION`。
- `domain/usecase/GetGroupedMediaUseCase.kt` — LOCATION 分组。
- `features/gallery/components/GalleryUtils.kt` — `resolveGroupTitle` 加 LOCATION/NO_LOCATION。
- `features/gallery/components/GalleryTopBar.kt` — `GroupingMenu` 加 LOCATION。
- `res/values{,-zh-rCN,-zh-rTW}/strings.xml` — 新增串 + 校验 `gallery_group_location`。

---

## Task 1: `ResolvedLocation` + 规范层级串（纯函数 TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/indexing/geo/ResolvedLocation.kt`
- Test: `app/src/test/java/com/mamba/picme/data/indexing/geo/ResolvedLocationTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.mamba.picme.data.indexing.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolvedLocationTest {
    @Test
    fun `display string dedupes municipality province equals city`() {
        val r = ResolvedLocation(province = "北京市", city = "北京市", district = "海淀区", poi = "中关村")
        assertEquals("北京市 海淀区 中关村", r.toDisplayString())
    }

    @Test
    fun `display string keeps distinct province and city`() {
        val r = ResolvedLocation(province = "广东省", city = "深圳市", district = "南山区")
        assertEquals("广东省 深圳市 南山区", r.toDisplayString())
    }

    @Test
    fun `null when all parts empty`() {
        assertNull(ResolvedLocation().toDisplayString())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.ResolvedLocationTest"`
Expected: FAIL（`ResolvedLocation` 未定义 / unresolved reference）

- [ ] **Step 3: Implement**

```kotlin
package com.mamba.picme.data.indexing.geo

/**
 * 逆地理编码统一结果：系统 Geocoder 与离线质心库都产出此类型。
 */
data class ResolvedLocation(
    val country: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val poi: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    /**
     * 规范层级展示串（如「北京市 海淀区 中关村」）。
     * [distinct] 去掉直辖市 province==city 的重复；全空返回 null。
     */
    fun toDisplayString(): String? =
        listOfNotNull(province, city, district, poi)
            .takeIf { it.isNotEmpty() }
            ?.distinct()
            ?.joinToString(" ")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.ResolvedLocationTest"`
Expected: PASS（3 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/indexing/geo/ResolvedLocation.kt \
        app/src/test/java/com/mamba/picme/data/indexing/geo/ResolvedLocationTest.kt
git commit -m "feat(geo): ResolvedLocation 统一逆地理结果 + 规范层级串"
```

---

## Task 2: `AdminCentroidIndex` 暴力最近邻（纯函数 TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/indexing/geo/Centroid.kt`
- Create: `app/src/main/java/com/mamba/picme/data/indexing/geo/AdminCentroidIndex.kt`
- Test: `app/src/test/java/com/mamba/picme/data/indexing/geo/AdminCentroidIndexTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.mamba.picme.data.indexing.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdminCentroidIndexTest {
    private val idx = AdminCentroidIndex(
        listOf(
            Centroid("北京市", "北京市", "东城区", 39.90, 116.40),
            Centroid("上海市", "上海市", "黄浦区", 31.23, 121.47),
            Centroid("广东省", "深圳市", "福田区", 22.54, 114.06)
        )
    )

    @Test
    fun `beijing coord resolves to beijing`() {
        assertEquals("北京市", idx.nearest(39.95, 116.32)!!.city)
    }

    @Test
    fun `shanghai coord resolves to shanghai`() {
        assertEquals("上海市", idx.nearest(31.10, 121.50)!!.city)
    }

    @Test
    fun `empty index returns null`() {
        assertNull(AdminCentroidIndex(emptyList()).nearest(30.0, 120.0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.AdminCentroidIndexTest"`
Expected: FAIL（`Centroid`/`AdminCentroidIndex` 未定义）

- [ ] **Step 3: Implement**

```kotlin
package com.mamba.picme.data.indexing.geo

/** 离线行政区划质心（地名库一行）。 */
data class Centroid(
    val province: String,
    val city: String,
    val district: String,
    val lat: Double,
    val lon: Double
)
```

```kotlin
package com.mamba.picme.data.indexing.geo

import kotlin.math.PI
import kotlin.math.cos

/**
 * 离线地名索引：暴力最近邻（~340 地级市，微秒级，无需 KD-tree）。
 * 纯 Kotlin、无 Android 依赖，便于 JVM 单测。
 */
class AdminCentroidIndex(private val centroids: List<Centroid>) {

    /** 距 (lat, lon) 最近的质心；库空返回 null。 */
    fun nearest(lat: Double, lon: Double): Centroid? {
        if (centroids.isEmpty()) return null
        var best = centroids[0]
        var bestD = distanceSq(lat, lon, best.lat, best.lon)
        for (i in 1 until centroids.size) {
            val c = centroids[i]
            val d = distanceSq(lat, lon, c.lat, c.lon)
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return best
    }

    /** 等距矩形近似距离平方（省略常数因子，仅用于比大小）。 */
    private fun distanceSq(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val la1 = lat1 * PI / 180.0
        val la2 = lat2 * PI / 180.0
        val dLat = la2 - la1
        val dLon = (lon2 - lon1) * PI / 180.0 * cos((la1 + la2) / 2.0)
        return dLat * dLat + dLon * dLon
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.AdminCentroidIndexTest"`
Expected: PASS（3 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/indexing/geo/Centroid.kt \
        app/src/main/java/com/mamba/picme/data/indexing/geo/AdminCentroidIndex.kt \
        app/src/test/java/com/mamba/picme/data/indexing/geo/AdminCentroidIndexTest.kt
git commit -m "feat(geo): AdminCentroidIndex 离线质心暴力最近邻"
```

---

## Task 3: `OfflineGeocoder` + JSON 解析（纯函数 TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/indexing/geo/OfflineGeocoder.kt`
- Test: `app/src/test/java/com/mamba/picme/data/indexing/geo/OfflineGeocoderTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.mamba.picme.data.indexing.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineGeocoderTest {
    @Test
    fun `parse centroids json`() {
        val json = """[{"province":"北京市","city":"北京市","district":"海淀区","lat":39.96,"lon":116.30}]"""
        val list = OfflineGeocoder.parseCentroids(json)
        assertEquals(1, list.size)
        assertEquals("海淀区", list[0].district)
        assertEquals(39.96, list[0].lat, 0.0)
    }

    @Test
    fun `lookup maps nearest centroid to resolved location with original coords`() {
        val geo = OfflineGeocoder(
            AdminCentroidIndex(listOf(Centroid("北京市", "北京市", "海淀区", 39.96, 116.30)))
        )
        val r = geo.lookup(39.95, 116.29)!!
        assertEquals("北京市", r.city)
        assertEquals(39.95, r.latitude!!, 0.0)
    }

    @Test
    fun `lookup null on empty index`() {
        assertNull(OfflineGeocoder(AdminCentroidIndex(emptyList())).lookup(30.0, 120.0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.OfflineGeocoderTest"`
Expected: FAIL（`OfflineGeocoder` 未定义）

- [ ] **Step 3: Implement**

```kotlin
package com.mamba.picme.data.indexing.geo

import android.content.Context
import com.mamba.picme.core.common.Logger
import org.json.JSONArray

/**
 * 端侧离线逆地理编码：经纬度 → 最近行政区划质心 → [ResolvedLocation]。
 */
class OfflineGeocoder(private val index: AdminCentroidIndex) {

    /** 最近邻匹配；库空或无命中返回 null。 */
    fun lookup(lat: Double, lon: Double): ResolvedLocation? =
        index.nearest(lat, lon)?.let { c ->
            ResolvedLocation(
                province = c.province,
                city = c.city,
                district = c.district,
                latitude = lat,
                longitude = lon
            )
        }

    companion object {
        private const val TAG = "PoLang:OfflineGeocoder"
        private const val ASSET = "geo/admin_centroids_zh.json"

        /** 从 assets 装载；资产缺失/损坏返回空库（lookup 恒为 null）。 */
        fun fromAssets(context: Context): OfflineGeocoder {
            val centroids = try {
                context.assets.open(ASSET).use { stream ->
                    parseCentroids(stream.bufferedReader().readText())
                }
            } catch (e: Exception) {
                Logger.w(TAG, "offline centroid asset unavailable: ${e.message}")
                emptyList()
            }
            return OfflineGeocoder(AdminCentroidIndex(centroids))
        }

        /** 解析 JSON 数组为质心列表（纯函数，便于 JVM 单测）。 */
        fun parseCentroids(json: String): List<Centroid> {
            val arr = JSONArray(json)
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Centroid(
                            province = o.getString("province"),
                            city = o.getString("city"),
                            district = o.getString("district"),
                            lat = o.getDouble("lat"),
                            lon = o.getDouble("lon")
                        )
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.OfflineGeocoderTest"`
Expected: PASS（3 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/indexing/geo/OfflineGeocoder.kt \
        app/src/test/java/com/mamba/picme/data/indexing/geo/OfflineGeocoderTest.kt
git commit -m "feat(geo): OfflineGeocoder lookup + assets 装载 + JSON 解析"
```

---

## Task 4: 种子地名资产

**Files:**
- Create: `app/src/main/assets/geo/admin_centroids_zh.json`

- [ ] **Step 1: Create seed asset**

写入以下种子（覆盖主要城市，保证 v1 可用；Task 5 用脚本替换为全国地级市）。Schema 与 `OfflineGeocoder.parseCentroids` 一致：`[{province, city, district, lat, lon}]`。

```json
[
  {"province":"北京市","city":"北京市","district":"东城区","lat":39.91,"lon":116.41},
  {"province":"北京市","city":"北京市","district":"海淀区","lat":39.96,"lon":116.30},
  {"province":"上海市","city":"上海市","district":"黄浦区","lat":31.23,"lon":121.47},
  {"province":"天津市","city":"天津市","district":"和平区","lat":39.12,"lon":117.20},
  {"province":"重庆市","city":"重庆市","district":"渝中区","lat":29.55,"lon":106.55},
  {"province":"广东省","city":"广州市","district":"越秀区","lat":23.13,"lon":113.26},
  {"province":"广东省","city":"深圳市","district":"福田区","lat":22.54,"lon":114.06},
  {"province":"浙江省","city":"杭州市","district":"西湖区","lat":30.27,"lon":120.15},
  {"province":"江苏省","city":"南京市","district":"玄武区","lat":32.05,"lon":118.78},
  {"province":"四川省","city":"成都市","district":"锦江区","lat":30.66,"lon":104.06},
  {"province":"湖北省","city":"武汉市","district":"江岸区","lat":30.60,"lon":114.30},
  {"province":"陕西省","city":"西安市","district":"莲湖区","lat":34.27,"lon":108.95},
  {"province":"山东省","city":"青岛市","district":"市南区","lat":36.07,"lon":120.38},
  {"province":"福建省","city":"厦门市","district":"思明区","lat":24.48,"lon":118.09},
  {"province":"云南省","city":"昆明市","district":"五华区","lat":25.05,"lon":102.71}
]
```

- [ ] **Step 2: Verify it parses (no test class needed — re-run Task 3 test as smoke)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.OfflineGeocoderTest"`
Expected: PASS（确认实现未回归；资产在打包时由 `fromAssets` 读取）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/geo/admin_centroids_zh.json
git commit -m "feat(geo): 内置主要城市质心种子数据"
```

---

## Task 5: 全国地级市质心生成脚本（数据增强）

**Files:**
- Create: `scripts/gen_admin_centroids.py`

> 目的：把种子替换为全国地级市/州质心（~340 行），来源 GeoNames（CC-BY 4.0，需署名）。索引代码不依赖行数，故此任务纯数据。

- [ ] **Step 1: Write generator**

```python
#!/usr/bin/env python3
# 生成 app/src/main/assets/geo/admin_centroids_zh.json
# 数据来源 GeoNames (https://download.geonames.org/export/dump/) —— CC-BY 4.0，需在「关于/隐私」署名。
#
# 依赖: 无第三方库(仅标准库)。下载 cities500.zip + admin1/admin2 code 表。
# 用法: python3 scripts/gen_admin_centroids.py
import csv
import io
import json
import urllib.request
import zipfile

BASE = "https://download.geonames.org/export/dump"
OUT = "app/src/main/assets/geo/admin_centroids_zh.json"

# cities500 列: geonameid,name,asciiname,alternatenames,lat,lon,featureClass,featureCode,
#               countryCode,cc2,admin1,admin2,...,population,...
CITY_URL = f"{BASE}/cities500.zip"
ADMIN1_URL = f"{BASE}/admin1CodesASCII.txt"
ADMIN2_URL = f"{BASE}/admin2Codes.txt"


def download_text(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def load_admin(path_url):
    """code -> name ; 形如 'CN.04' -> 中文名(优先 alternatenames 里中文,回退 name)。"""
    mapping = {}
    data = download_text(path_url)
    for row in csv.reader(io.StringIO(data.decode("utf-8")), delimiter="\t"):
        if len(row) < 4:
            continue
        code, name, _ascii, _geoid = row[0], row[1], row[2], row[3]
        if not code.startswith("CN."):
            continue
        alts = row[3].split(",") if len(row) > 3 else []
        zh = next((a for a in alts if any("一" <= ch <= "鿿" for ch in a)), name)
        mapping[code] = zh
    return mapping


def main():
    admin1 = load_admin(ADMIN1_URL)  # 注意 admin1CodesASCII 列结构略有差异,见下注释
    admin2 = load_admin(ADMIN2_URL)

    data = download_text(CITY_URL)
    rows = []
    seen = set()
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        fname = z.namelist()[0]
        for line in io.TextIOWrapper(z.open(fname), encoding="utf-8"):
            f = line.split("\t")
            if len(f) < 18 or f[8] != "CN":
                continue
            lat, lon = float(f[4]), float(f[5])
            a1, a2 = f[10], f[11]
            province = admin1.get(f"CN.{a1}", "")
            city = admin2.get(f"CN.{a1}.{a2}", "") or province
            name = f[1]
            zh = next((alt for alt in f[3].split(",") if any("一" <= c <= "鿿" for c in alt)), name)
            district = zh
            key = (province, city, district)
            if key in seen or not province:
                continue
            seen.add(key)
            rows.append({"province": province, "city": city, "district": district, "lat": lat, "lon": lon})

    rows.sort(key=lambda r: (r["province"], r["city"], r["district"]))
    with open(OUT, "w", encoding="utf-8") as fp:
        json.dump(rows, fp, ensure_ascii=False, indent=2)
    print(f"wrote {len(rows)} centroids -> {OUT}")
    print("attribution: GeoNames CC-BY 4.0 — 在「关于/隐私」页署名 http://www.geonames.org")


if __name__ == "__main__":
    main()
```

> 注：GeoNames 各 dump 的列结构可能逐年微调。若脚本产出为空或字段错位，对照官方 README 校准列下标（`admin1CodesASCII.txt` 实为 `code name ascii geonameid`，不含 alternatenames；必要时把 admin1 中文名回退用 `cities500` 同 admin1 下人口最大城市的中文 alternatenames）。这是数据校准，不影响代码。

- [ ] **Step 2: Run generator + verify output**

Run: `python3 scripts/gen_admin_centroids.py`
Expected: 控制台打印 `wrote <N> centroids`（N 期望 > 200），并打印署名提示。

校验：确认输出含北京/上海/深圳等：
Run: `grep -c '"district"' app/src/main/assets/geo/admin_centroids_zh.json`（应 > 200）
Run: `grep "北京市" app/src/main/assets/geo/admin_centroids_zh.json | head -1`（应命中）

- [ ] **Step 3: Re-run offline tests against real asset size (smoke)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.*"`
Expected: PASS（最近邻逻辑与行数无关）

- [ ] **Step 4: Commit**

```bash
git add scripts/gen_admin_centroids.py app/src/main/assets/geo/admin_centroids_zh.json
git commit -m "feat(geo): 全国地级市质心生成脚本 + 数据(GeoNames CC-BY 4.0)"
```

---

## Task 6: `MetadataExtractor` 离线兜底 + `Address→ResolvedLocation`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/indexing/MetadataExtractor.kt`
- Test: `app/src/test/java/com/mamba/picme/data/indexing/geo/MetadataExtractorMappingTest.kt`

- [ ] **Step 1: Write failing test（Address→ResolvedLocation 映射，Robolectric）**

```kotlin
package com.mamba.picme.data.indexing.geo

import android.location.Address
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MetadataExtractorMappingTest {
    @Test
    fun `Address maps to ResolvedLocation with coords`() {
        val addr = Address(Locale.CHINA).apply {
            countryName = "中国"
            adminArea = "广东省"
            locality = "深圳市"
            subLocality = "南山区"
            featureName = "世界之窗"
        }
        val r = addr.toResolvedLocation(22.53, 113.97)
        assertEquals("中国", r.country)
        assertEquals("广东省", r.province)
        assertEquals("深圳市", r.city)
        assertEquals("南山区", r.district)
        assertEquals("世界之窗", r.poi)
        assertEquals(22.53, r.latitude!!, 0.0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.MetadataExtractorMappingTest"`
Expected: FAIL（`toResolvedLocation` 未定义）

- [ ] **Step 3: Implement — rewrite MetadataExtractor location pieces**

在 `MetadataExtractor.kt` 顶部 `import android.location.Address` 已存在；新增 `import com.mamba.picme.data.indexing.geo.OfflineGeocoder` 与 `import com.mamba.picme.data.indexing.geo.ResolvedLocation`。

**3a. 文件末尾新增顶层 internal 扩展（可测）：**

```kotlin
/** 把系统 Geocoder 的 [Address] 映射为 [ResolvedLocation]（internal 便于单测）。 */
internal fun Address.toResolvedLocation(lat: Double, lon: Double): ResolvedLocation = ResolvedLocation(
    country = countryName,
    province = adminArea,
    city = locality,
    district = subLocality,
    poi = featureName,
    latitude = lat,
    longitude = lon
)
```

**3b. 构造函数增加 `offlineGeocoder`（默认从 assets 装载）：**

把
```kotlin
class MetadataExtractor(
    private val context: Context,
    private val idCardRecognizer: IdCardRecognizer? = null
) {
```
改为
```kotlin
class MetadataExtractor(
    private val context: Context,
    private val idCardRecognizer: IdCardRecognizer? = null,
    private val offlineGeocoder: OfflineGeocoder = OfflineGeocoder.fromAssets(context)
) {
```

**3c. `extractLocation` + `reverseGeocode` 返回 `ResolvedLocation`：**

把现有 `extractLocation`/`reverseGeocode`（约 87-123 行）整体替换为：

```kotlin
    /**
     * EXIF 位置提取 + 逆地理编码（系统 Geocoder 优先，失败走离线兜底）。
     */
    private fun extractLocation(imageUri: Uri): ResolvedLocation? {
        return try {
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = exif.latLong
                val lat = latLong?.getOrNull(0)
                val lon = latLong?.getOrNull(1)
                if (lat != null && lon != null) reverseGeocode(lat, lon) else null
            }
        } catch (e: IOException) {
            Logger.w(tag, "EXIF location extraction failed", e)
            null
        }
    }

    /**
     * 逆地理编码：经纬度 → [ResolvedLocation]。系统 Geocoder 失败/为空 → 离线质心兜底。
     */
    private fun reverseGeocode(lat: Double, lon: Double): ResolvedLocation? {
        val addresses = try {
            Geocoder(context).getFromLocation(lat, lon, 1)
        } catch (e: IOException) {
            Logger.w(tag, "Geocoder failed, will try offline", e)
            null
        }
        val addr = addresses?.firstOrNull()
        return if (addr != null) addr.toResolvedLocation(lat, lon)
        else offlineGeocoder.lookup(lat, lon)
    }
```

删除原 `reverseGeocode(latitude, longitude)` 旧实现（被上面取代）。

**3d. `ExtractionResult` 改为基于 `resolved`：**

把现有 `ExtractionResult` data class（约 133-145 行）替换为：

```kotlin
    data class ExtractionResult(
        val labels: List<String> = emptyList(),
        val ocrText: String? = null,
        val resolved: ResolvedLocation? = null
    ) {
        val latitude: Double? get() = resolved?.latitude
        val longitude: Double? get() = resolved?.longitude
        val locationName: String? get() = resolved?.toDisplayString()

        val labelsJson: String?
            get() = labels.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "[", postfix = "]") { label ->
                    "\"${label}\""
                }
    }
```

删除原 `private data class LocationData(...)`（不再使用）。

**3e. `extract()` 改用 `resolved`：**

把
```kotlin
    suspend fun extract(imageUri: Uri, inputImage: InputImage): ExtractionResult {
        val ocrText = extractOcrWithIdCardFallback(inputImage)
        val (latitude, longitude, locationName) = extractLocation(imageUri)

        return ExtractionResult(emptyList(), ocrText, latitude, longitude, locationName)
    }
```
改为
```kotlin
    suspend fun extract(imageUri: Uri, inputImage: InputImage): ExtractionResult {
        val ocrText = extractOcrWithIdCardFallback(inputImage)
        val resolved = extractLocation(imageUri)

        return ExtractionResult(emptyList(), ocrText, resolved)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.MetadataExtractorMappingTest"`
Expected: PASS（1 test）

- [ ] **Step 5: Compile-check the app module (call sites still use `result.latitude/locationName` derived props)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`ExtractionResult.latitude/longitude/locationName` 现为派生属性，旧调用点无需改动）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/indexing/MetadataExtractor.kt \
        app/src/test/java/com/mamba/picme/data/indexing/geo/MetadataExtractorMappingTest.kt
git commit -m "feat(geo): MetadataExtractor 接入离线兜底,产出 ResolvedLocation"
```

---

## Task 7: `LocationIndexUpdater` 接收 `ResolvedLocation`（补全省/市/区）+ worker 调用点

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/indexing/LocationIndexUpdater.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/indexing/MediaIndexingWorker.kt:138-143, 219-224`
- Test: `app/src/test/java/com/mamba/picme/data/indexing/geo/LocationIndexUpdaterTest.kt`

- [ ] **Step 1: Write failing test（Robolectric + Room 内存库）**

```kotlin
package com.mamba.picme.data.indexing.geo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.data.indexing.LocationIndexUpdater
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationIndexUpdaterTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: LocationDao
    private lateinit var updater: LocationIndexUpdater

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.locationDao()
        updater = LocationIndexUpdater(dao)
    }

    @After
    fun teardown() = db.close()

    @Test
    fun `updateIndex writes province city district from resolved`() = runTest {
        val mediaId = db.mediaDao().insertMedia(
            MediaEntity(uri = "content://x/1", type = MediaType.PHOTO, captureDate = 1L, fileName = "a.jpg")
        )
        updater.updateIndex(
            mediaId,
            ResolvedLocation(province = "广东省", city = "深圳市", district = "南山区", latitude = 22.53, longitude = 113.97)
        )
        val loc = dao.findByCoordinate(22.53, 113.97)
        assertNotNull(loc)
        assertEquals("广东省", loc!!.province)
        assertEquals("深圳市", loc.city)
        assertEquals("南山区", loc.district)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.LocationIndexUpdaterTest"`
Expected: FAIL（`updateIndex(mediaId, ResolvedLocation?)` 签名不存在）

- [ ] **Step 3: Rewrite `LocationIndexUpdater.updateIndex`**

新增 import `com.mamba.picme.data.indexing.geo.ResolvedLocation`，删除 `import android.location.Address`。把 `updateIndex(...)` 整段（含 `address: Address?` 入参）替换为：

```kotlin
    /**
     * 更新指定媒体的地理索引。
     *
     * @param mediaId 媒体 ID
     * @param resolved 逆地理编码结果（含省/市/区/POI 与坐标）；为 null 或无坐标则清空并返回
     */
    suspend fun updateIndex(mediaId: Long, resolved: ResolvedLocation?) {
        locationDao.clearLocationsForMedia(mediaId)
        val lat = resolved?.latitude ?: return
        val lon = resolved.longitude ?: return

        try {
            val existingLoc = locationDao.findByCoordinate(lat, lon)
            val locationId: Long = if (existingLoc != null) {
                existingLoc.locationId
            } else {
                locationDao.insertLocation(
                    LocationHierarchyEntity(
                        country = resolved.country,
                        province = resolved.province,
                        city = resolved.city,
                        district = resolved.district,
                        poi = resolved.poi,
                        latitude = roundCoordinate(lat),
                        longitude = roundCoordinate(lon)
                    )
                )
            }
            locationDao.insertMediaLocation(MediaLocationEntity(mediaId = mediaId, locationId = locationId))
            Logger.d(TAG, "Location index updated for media $mediaId -> loc $locationId")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to update location for media $mediaId: ${e.message}")
        }
    }
```

- [ ] **Step 4: Update worker call sites (两处)**

`MediaIndexingWorker.kt:138-143` 改为：
```kotlin
                        locationIndexUpdater.updateIndex(
                            mediaId = entity.id,
                            resolved = result.resolved
                        )
```
`MediaIndexingWorker.kt:219-224` 改为：
```kotlin
            locationIdxUpdater.updateIndex(
                mediaId = mediaId,
                resolved = result.resolved
            )
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.LocationIndexUpdaterTest"`
Expected: PASS（1 test）

- [ ] **Step 6: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/indexing/LocationIndexUpdater.kt \
        app/src/main/java/com/mamba/picme/data/indexing/MediaIndexingWorker.kt \
        app/src/test/java/com/mamba/picme/data/indexing/geo/LocationIndexUpdaterTest.kt
git commit -m "feat(geo): LocationIndexUpdater 接收 ResolvedLocation,补全省/市/区"
```

---

## Task 8: `MediaEntity.city` + `MediaAsset.city` + migration 15→16 + DAO/worker/toDomain 接线

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt:40`（加 `city`）
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/MediaAsset.kt:21`（加 `city`）
- Modify: `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt:55,85`（version 16 + MIGRATION_15_16）
- Modify: `app/src/main/java/com/mamba/picme/data/local/MediaDao.kt:90-109`（`updateIndexResult` 加 `city`）
- Modify: `app/src/main/java/com/mamba/picme/data/indexing/MediaIndexingWorker.kt:127-135,151-158,190-197,203-214`（传 `city`）
- Modify: `app/src/main/java/com/mamba/picme/data/repository/MediaRepositoryImpl.kt:529-537`（`toDomain` 映射 `city`）
- Test: `app/src/test/java/com/mamba/picme/data/local/dao/MediaDaoCityTest.kt`

- [ ] **Step 1: Write failing test（city 列读写，Robolectric）**

```kotlin
package com.mamba.picme.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaDaoCityTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun `city column round-trips via updateIndexResult`() = runTest {
        val dao = db.mediaDao()
        val id = dao.insertMedia(MediaEntity(uri = "content://x/1", type = MediaType.PHOTO, captureDate = 1L, fileName = "a.jpg"))
        dao.updateIndexResult(
            mediaId = id, labels = null, ocrText = null, latitude = 22.5, longitude = 113.9,
            locationName = "深圳市", city = "深圳市", indexedAt = 1L
        )
        val reloaded = dao.getMediaByIds(listOf(id)).first()
        assertEquals("深圳市", reloaded.city)
    }

    @Test
    fun `city defaults to null for legacy rows`() = runTest {
        val dao = db.mediaDao()
        val id = dao.insertMedia(MediaEntity(uri = "content://x/2", type = MediaType.PHOTO, captureDate = 1L, fileName = "b.jpg"))
        assertNull(dao.getMediaByIds(listOf(id)).first().city)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.local.dao.MediaDaoCityTest"`
Expected: FAIL（`updateIndexResult` 无 `city` 参数 / `MediaEntity.city` 不存在）

- [ ] **Step 3: Add `city` to models**

`MediaEntity.kt` 在 `locationName` 行后加：
```kotlin
    val locationName: String? = null,     // 逆地理编码地名
    val city: String? = null,             // 逆地理编码城市（去范式化,供按城市分组）
```
`MediaAsset.kt`（runtime-core）在 `locationName` 行后加：
```kotlin
    val locationName: String? = null,
    val city: String? = null,
```

- [ ] **Step 4: Migration 15→16**

`AppDatabase.kt:55` `version = 15` → `version = 16`。
在 `addMigrations(...)` 列表末尾、最后一个 `MIGRATION_14_15` 之后新增：

```kotlin
        /**
         * Migration 15 → 16：media_assets 新增 city 列（逆地理编码城市，去范式化供按城市分组）。
         * 只 ADD COLUMN（全版本 SQLite 安全）；存量行 city 为 NULL，由回填 pass 写入。
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `media_assets` ADD COLUMN `city` TEXT")
            }
        }
```
并在 `addMigrations(...)` 调用里追加 `MIGRATION_15_16,`（与既有迁移同列）。

> 实操定位：`grep -n "MIGRATION_14_15\|addMigrations(" app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt` —— 在 14_15 定义之后插入新迁移；在 `addMigrations(` 的括号参数列表末尾加 `MIGRATION_15_16`。

- [ ] **Step 5: `updateIndexResult` 加 `city`**

`MediaDao.kt:90-109` 的 `@Query` UPDATE 的 SET 子句加一行 `city = :city,`；函数签名加 `city: String?,`：

```kotlin
    @Query(
        """
        UPDATE media_assets SET
            labels = :labels,
            ocrText = :ocrText,
            latitude = :latitude,
            longitude = :longitude,
            locationName = :locationName,
            city = :city,
            indexedAt = :indexedAt
        WHERE id = :mediaId
        """
    )
    suspend fun updateIndexResult(
        mediaId: Long,
        labels: String?,
        ocrText: String?,
        latitude: Double?,
        longitude: Double?,
        locationName: String?,
        city: String?,
        indexedAt: Long
    )
```

- [ ] **Step 6: Worker 传 `city`（四处）**

成功路径 `MediaIndexingWorker.kt:127-135` 在 `locationName = result.locationName,` 后加 `city = result.resolved?.city,`。
错误路径 `MediaIndexingWorker.kt:151-158` 在 `locationName = entity.locationName,` 后加 `city = entity.city,`。
单图成功路径 `MediaIndexingWorker.kt:190-197` 在 `locationName = result.locationName,` 后加 `city = result.resolved?.city,`。
新建实体 `MediaIndexingWorker.kt:203-214` 在 `locationName = result.locationName,` 后加 `city = result.resolved?.city,`。

- [ ] **Step 7: `toDomain` 映射 `city`**

`MediaRepositoryImpl.kt:529` `MediaEntity.toDomain(): MediaAsset` 在 `locationName = locationName,` 后加 `city = city,`。

- [ ] **Step 8: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.local.dao.MediaDaoCityTest"`
Expected: PASS（2 tests）

- [ ] **Step 9: Compile full app**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`MediaAsset`/`MediaEntity` 的 `city` 有默认值，其它构造点不破）

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt \
        runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/MediaAsset.kt \
        app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt \
        app/src/main/java/com/mamba/picme/data/local/MediaDao.kt \
        app/src/main/java/com/mamba/picme/data/indexing/MediaIndexingWorker.kt \
        app/src/main/java/com/mamba/picme/data/repository/MediaRepositoryImpl.kt \
        app/src/test/java/com/mamba/picme/data/local/dao/MediaDaoCityTest.kt
git commit -m "feat(geo): MediaEntity/MediaAsset city 列 + migration 15->16 + 接线"
```

---

## Task 9: 有界回填 pass（lat 有、locationName 空的历史媒体）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/local/MediaDao.kt`（加回填查询）
- Modify: `app/src/main/java/com/mamba/picme/data/indexing/MediaIndexingWorker.kt`（加 `backfillLocations()` + 在索引入口调用一次）
- Test: `app/src/test/java/com/mamba/picme/data/indexing/geo/BackfillSelectorTest.kt`

- [ ] **Step 1: Write failing test（选择器为 DAO 查询；此处测回填后的字段更新逻辑用纯函数 `applyOfflineBackfill`）**

```kotlin
package com.mamba.picme.data.indexing.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackfillSelectorTest {
    @Test
    fun `applyOfflineBackfill fills name and city when offline resolves`() {
        val original = com.mamba.picme.data.indexing.MetadataExtractorBackfill.apply(
            latitude = 22.53,
            longitude = 113.97,
            offline = OfflineGeocoder(
                AdminCentroidIndex(listOf(Centroid("广东省", "深圳市", "福田区", 22.54, 114.06)))
            )
        )
        assertEquals("深圳市", original?.city)
        // locationName = 规范层级串
        assertEquals("广东省 深圳市 福田区", original?.locationName)
    }

    @Test
    fun `applyOfflineBackfill null when coords missing`() {
        assertNull(
            com.mamba.picme.data.indexing.MetadataExtractorBackfill.apply(
                latitude = null, longitude = null, offline = OfflineGeocoder(AdminCentroidIndex(emptyList()))
            )
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.BackfillSelectorTest"`
Expected: FAIL（`MetadataExtractorBackfill` 未定义）

- [ ] **Step 3: Implement backfill helper + DAO query + worker pass**

`MediaDao.kt` 新增查询：
```kotlin
    /** 回填选择：有坐标但无地名的存量媒体（历史上 Geocoder 失败）。 */
    @Query(
        """
        SELECT * FROM media_assets
        WHERE latitude IS NOT NULL AND longitude IS NOT NULL
          AND (locationName IS NULL OR locationName = '')
        """
    )
    suspend fun getMediaNeedingLocationBackfill(): List<MediaEntity>
```

`MediaIndexingWorker.kt` 新增 object（可测纯逻辑）：
```kotlin
    /** 回填纯逻辑：用离线结果派生 city + 规范层级串。无坐标返回 null。 */
    object BackfillResolver {
        fun resolve(
            latitude: Double?,
            longitude: Double?,
            offline: com.mamba.picme.data.indexing.geo.OfflineGeocoder
        ): Pair<String?, String?>? {
            val lat = latitude ?: return null
            val lon = longitude ?: return null
            val resolved = offline.lookup(lat, lon) ?: return null
            return resolved.city to resolved.toDisplayString()
        }
    }
```
（测试里把 `MetadataExtractorBackfill.apply` 改名为 `BackfillResolver.resolve`——以 Step 3 实现为准；同步修正 Step 1 测试中的引用为 `BackfillResolver.resolve(...)`，返回 `Pair(city, locationName)`。）

> 自洽修正：Step 1 测试中 `MetadataExtractorBackfill.apply` 改为 `BackfillResolver.resolve(latitude, longitude, offline)`，断言 `original?.first == "深圳市"`、`original?.second == "广东省 深圳市 福田区"`、`null` 用例不变。

`MediaIndexingWorker.kt` 在批量索引循环之前（`try {` 之后、`while` 之前，约 104 行处）调用一次：
```kotlin
            backfillLocations(db, OfflineGeocoder.fromAssets(context))
```
并新增方法：
```kotlin
    private suspend fun backfillLocations(
        db: AppDatabase,
        offline: com.mamba.picme.data.indexing.geo.OfflineGeocoder
    ) {
        val dao = db.mediaDao()
        val updater = LocationIndexUpdater(db.locationDao())
        val pending = dao.getMediaNeedingLocationBackfill()
        if (pending.isEmpty()) return
        Logger.i(TAG, "Location backfill: ${pending.size} media")
        for (entity in pending) {
            val (city, locationName) = BackfillResolver.resolve(entity.latitude, entity.longitude, offline)
                ?: continue
            dao.updateIndexResult(
                mediaId = entity.id,
                labels = entity.labels,
                ocrText = entity.ocrText,
                latitude = entity.latitude,
                longitude = entity.longitude,
                locationName = locationName,
                city = city,
                indexedAt = entity.indexedAt ?: -1L
            )
            updater.updateIndex(
                entity.id,
                com.mamba.picme.data.indexing.geo.ResolvedLocation(
                    city = city, latitude = entity.latitude, longitude = entity.longitude
                )
            )
        }
    }
```
（`LocationIndexUpdater`/`OfflineGeocoder`/`ResolvedLocation` 已在 Task 6/7 引入；按需补 import。）

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.indexing.geo.BackfillSelectorTest"`
Expected: PASS（2 tests）

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/local/MediaDao.kt \
        app/src/main/java/com/mamba/picme/data/indexing/MediaIndexingWorker.kt \
        app/src/test/java/com/mamba/picme/data/indexing/geo/BackfillSelectorTest.kt
git commit -m "feat(geo): 有界位置回填 pass(lat 有/locationName 空的历史媒体)"
```

---

## Task 10: `PhotoInfoDialog` 位置行（`geo:` intent，隐藏裸经纬度）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt:1399-1413`
- Test: `app/src/test/java/com/mamba/picme/features/gallery/GeoUriTest.kt`（纯函数）
- Strings: `res/values{,-zh-rCN,-zh-rTW}/strings.xml`

- [ ] **Step 1: Write failing test（`buildGeoUri` 纯函数）**

```kotlin
package com.mamba.picme.features.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoUriTest {
    @Test
    fun `builds geo uri with q label`() {
        assertEquals(
            "geo:22.53,113.97?q=22.53,113.97(世界之窗)",
            buildGeoUri(22.53, 113.97, "世界之窗")
        )
    }

    @Test
    fun `encodes parentheses in label`() {
        assertEquals(
            "geo:1.0,2.0?q=1.0,2.0(a%28b)",
            buildGeoUri(1.0, 2.0, "a(b)")
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.gallery.GeoUriTest"`
Expected: FAIL（`buildGeoUri` 未定义）

- [ ] **Step 3: Implement `buildGeoUri` + 替换位置行**

`MediaPager.kt` 在文件顶层（`InfoRow` 附近，约 1603 行后）新增纯函数：
```kotlin
/** 构造 geo: intent URI，label 里的括号等特殊字符做百分号编码。 */
fun buildGeoUri(lat: Double, lon: Double, label: String): String {
    val encoded = android.net.Uri.encode(label)
    return "geo:$lat,$lon?q=$lat,$lon($encoded)"
}
```

新增字符串（三个 locale，见 Task 12 统一处理；此处先引用 `R.string.media_info_location` 与 `R.string.no_map_app`）。

替换 `MediaPager.kt:1399-1413` 的位置块：
```kotlin
                InfoRow("来源", asset.source!!.replaceFirstChar { it.uppercase() })
                val locName = asset.locationName
                if (!locName.isNullOrBlank()) {
                    LocationInfoRow(
                        label = context.getString(R.string.media_info_location),
                        locationName = locName,
                        lat = asset.latitude,
                        lon = asset.longitude,
                        context = context
                    )
                }
```
（删除原 `if (asset.locationName != null) { ... } else if (asset.latitude ... GPS ...) }` 整段。）

新增 Composable（紧邻 `InfoRow`）：
```kotlin
@Composable
private fun LocationInfoRow(
    label: String,
    locationName: String,
    lat: Double?,
    lon: Double?,
    context: android.content.Context
) {
    val canOpenMap = lat != null && lon != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canOpenMap) Modifier.clickable { openMapApp(context, lat!!, lon!!, locationName) } else Modifier)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.width(64.dp))
        Text(locationName, fontSize = 13.sp, color = Color.White)
    }
}

private fun openMapApp(context: android.content.Context, lat: Double, lon: Double, label: String) {
    val intent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse(buildGeoUri(lat, lon, label))
    )
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, context.getString(R.string.no_map_app), android.widget.Toast.LENGTH_SHORT).show()
    }
}
```
补 import：`androidx.compose.foundation.clickable`、`androidx.compose.ui.unit.dp`（若未导入）、`com.mamba.picme.R`。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.gallery.GeoUriTest"`
Expected: PASS（2 tests）

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt \
        app/src/test/java/com/mamba/picme/features/gallery/GeoUriTest.kt
git commit -m "feat(gallery): PhotoInfoDialog 位置行接 geo: intent,隐藏裸经纬度"
```

---

## Task 11: `GroupingMode.LOCATION` 按城市分组

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/model/MediaGrouping.kt`
- Modify: `app/src/main/java/com/mamba/picme/domain/usecase/GetGroupedMediaUseCase.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/GalleryUtils.kt:17-30`
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/GalleryTopBar.kt:172-181`
- Test: `app/src/test/java/com/mamba/picme/domain/usecase/GetGroupedMediaLocationTest.kt`
- Strings: 见 Task 12（`group_no_location`）

- [ ] **Step 1: Write failing test**

```kotlin
package com.mamba.picme.domain.usecase

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.model.GroupTitleType
import com.mamba.picme.domain.model.GroupingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class GetGroupedMediaLocationTest {
    private val usecase = GetGroupedMediaUseCase()

    private fun asset(id: Long, city: String?) = MediaAsset(
        id = id, uri = "u$id", type = MediaType.PHOTO, captureDate = id, fileName = "f$id", city = city
    )

    @Test
    fun `LOCATION groups by city with no-city bucket last`() {
        val groups = usecase(
            listOf(asset(1, "深圳市"), asset(2, "深圳市"), asset(3, "杭州市"), asset(4, null)),
            GroupingMode.LOCATION
        )
        assertEquals(3, groups.size)
        val sz = groups.first { it.titleValue == "深圳市" }
        assertEquals(2, sz.items.size)
        assertEquals(GroupTitleType.LOCATION, sz.titleType)
        assertEquals(GroupTitleType.NO_LOCATION, groups.last().titleType)
        assertEquals(1, groups.last().items.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.usecase.GetGroupedMediaLocationTest"`
Expected: FAIL（`GroupingMode.LOCATION` 不存在 / `MediaAsset` 构造缺 city——city 已在 Task 8 加入）

- [ ] **Step 3: Implement**

`MediaGrouping.kt` 枚举补值：
```kotlin
enum class GroupingMode {
    NONE, DATE, FACE, PERSON, LANDSCAPE, SWIMWEAR, SEXY,
    LOCATION
}

enum class GroupTitleType {
    NONE, DATE, WITH_FACES, NO_FACES, PERSON, LANDSCAPE, SWIMWEAR, SEXY, SEARCH,
    LOCATION, NO_LOCATION
}
```

`GetGroupedMediaUseCase` 的 `when (mode)` 末尾新增分支（在 `SEXY ->` 之后、`return when` 闭合前；确保枚举穷尽）：
```kotlin
            GroupingMode.LOCATION -> {
                val withCity = media.filter { !it.city.isNullOrBlank() }
                val noCity = media.filter { it.city.isNullOrBlank() }
                buildList {
                    withCity.groupBy { it.city!! }
                        .map { (city, items) -> GroupedMedia(GroupTitleType.LOCATION, city, items) }
                        .forEach { add(it) }
                    if (noCity.isNotEmpty()) {
                        add(GroupedMedia(GroupTitleType.NO_LOCATION, "", noCity))
                    }
                }
            }
```

`GalleryUtils.kt` `resolveGroupTitle` 的 `when` 补：
```kotlin
        GroupTitleType.LOCATION -> group.titleValue
        GroupTitleType.NO_LOCATION -> context.getString(R.string.group_no_location)
```

`GalleryTopBar.kt` `GroupingMenu` 的 `when (mode)`（约 172-181）补：
```kotlin
                    LOCATION -> stringResource(R.string.gallery_group_location)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.usecase.GetGroupedMediaLocationTest"`
Expected: PASS（1 test）

- [ ] **Step 5: Compile（枚举穷尽会强制 GalleryUtils/GalleryTopBar 都已补，否则编译失败）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/model/MediaGrouping.kt \
        app/src/main/java/com/mamba/picme/domain/usecase/GetGroupedMediaUseCase.kt \
        app/src/main/java/com/mamba/picme/features/gallery/components/GalleryUtils.kt \
        app/src/main/java/com/mamba/picme/features/gallery/components/GalleryTopBar.kt \
        app/src/test/java/com/mamba/picme/domain/usecase/GetGroupedMediaLocationTest.kt
git commit -m "feat(gallery): GroupingMode.LOCATION 按城市分组"
```

---

## Task 12: i18n 同步 + 隐私文案 + 全量构建验证

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

- [ ] **Step 1: Add / verify strings in all three locales**

新增 3 个串，三语同步（EN 为 default）：

`values/strings.xml`：
```xml
    <string name="media_info_location">Location</string>
    <string name="no_map_app">No map app found</string>
    <string name="group_no_location">No location</string>
```
`values-zh-rCN/strings.xml`：
```xml
    <string name="media_info_location">位置</string>
    <string name="no_map_app">未找到地图应用</string>
    <string name="group_no_location">无位置</string>
```
`values-zh-rTW/strings.xml`：
```xml
    <string name="media_info_location">位置</string>
    <string name="no_map_app">找不到地圖應用程式</string>
    <string name="group_no_location">無位置</string>
```

校验 `gallery_group_location`（By Location / 按地点 / 按地點）三语均已存在；若 `values-zh-rCN` / `values-zh-rTW` 缺失则补：
```xml
    <string name="gallery_group_location">按地点</string>   <!-- zh-rCN -->
    <string name="gallery_group_location">按地點</string>   <!-- zh-rTW -->
```

- [ ] **Step 2: Verify no hardcoded user-facing strings in new code**

Run: `grep -nE "\"位置\"|\"无位置\"|\"未找到" app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt app/src/main/java/com/mamba/picme/domain -r`
Expected: 无输出（全部走 string resource）

- [ ] **Step 3: 隐私文案核验**

`values-zh-rCN/strings.xml` 的 `data_privacy_local_body`（约 799 行）已含「媒体地理位置…本地处理」。确认未把逆地理改成网络即可；无需改动则跳过。若文案未提「地理位置」，补「媒体地理位置在本地解析」。

- [ ] **Step 4: Full build + all JVM tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部新增测试 PASS，无回归失败。

> 关于 detekt/ktlint：本项目当前 detekt 有预存失败、ktlint 插件状态异常（非真质量门，见项目实情）。若 `./gradlew :app:assembleDebug` 通过即视为编译门达标；额外跑 `./gradlew :app:ktlintCheck` 仅作参考，失败不阻断。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml \
        app/src/main/res/values-zh-rTW/strings.xml
git commit -m "i18n(gallery): 位置/无位置/无地图应用 三语同步"
```

- [ ] **Step 6: 设备验证（手动，可选但推荐）**

Run: `adb install -r app/build/outputs/apk/debug/polang-debug.apk` 后：
1. 打开一张带 GPS 的照片 → 详情「位置」行显示「省 市 区」→ 点击跳地图 App。
2. 相册分组菜单选「按地点」→ 按城市分组生效。
3. 搜索框输入城市名（如「深圳」）→ 命中该城市照片（回填后）。
4. `adb logcat -s "PoLang:MetadataExtractor:*" "PoLang:OfflineGeocoder:*" "PoLang:LocIndex:*"` 确认离线兜底/回填日志。

---

## Self-Review

**Spec 覆盖核对：**
- §3.1 离线核心（`AdminCentroidIndex`/`OfflineGeocoder`/`ResolvedLocation`/asset）→ Task 1-4，数据增强 Task 5。✅
- §3.2 `MetadataExtractor` 兜底 + `LocationIndexUpdater` 接 `ResolvedLocation` → Task 6、7。✅
- §3.3 存量回填 → Task 9。✅
- §3.4 单图展示（`geo:`、隐藏经纬度、无 GPS 隐藏）→ Task 10。✅
- §3.5 检索：`searchByPlace` 已接线（回填后生效，无需新代码）+ 显式「地点」维度落地为 `GroupingMode.LOCATION` → Task 11。✅
- §4 数据模型/migration/接线 → Task 8。✅
- §6 错误处理（资产缺失、Geocoder 异常、无 GPS）→ 内嵌于 Task 3/6/10 实现。✅
- §7 测试 → 每个任务 TDD；`searchByPlace` 命中由既有搜索测试 + Task 7 层级写入间接覆盖。✅
- §8 隐私文案 → Task 12 Step 3。✅
- §9 开放问题：离线源 → GeoNames（Task 5）；「地点」落位 → GroupingMode.LOCATION（Task 11）；经纬度可折叠 → v1 不做（YAGNI）。✅

**Placeholder 扫描：** 无 TBD/TODO；Task 5 脚本注明 GeoNames 列结构需现场校准（数据校准，非代码占位）；Task 8/11 的 UI 改动给出行号 + 具体代码。

**类型一致性：** `ResolvedLocation`/`Centroid`/`OfflineGeocoder.lookup`/`updateIndex(mediaId, ResolvedLocation?)`/`updateIndexResult(... city)`/`MediaAsset.city`/`MediaEntity.city`/`GroupingMode.LOCATION`/`GroupTitleType.LOCATION|NO_LOCATION`/`buildGeoUri` 在各任务间签名一致。Task 9 内已自洽修正测试引用（`BackfillResolver.resolve` 返回 `Pair`）。
