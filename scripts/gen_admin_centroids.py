#!/usr/bin/env python3
# 生成 androidApp/src/main/assets/geo/admin_centroids_zh.json
#
# 数据来源 GeoNames (https://download.geonames.org/export/dump/) —— CC-BY 4.0，
# 须在「关于/隐私」页署名 http://www.geonames.org。
#
# 策略（稳健、可复现）：
#   1. 下载 cities500.zip，过滤 countryCode==CN，province 用下方 admin1 码表查得；
#   2. city/district 用该行 alternatenames 里的首个中文名，回退 asciiname；
#   3. 人口阈值 POP_MIN 过滤——保留县级及以上聚落，剔除「冈厦/Huafu」等小社区，
#      避免逆地理出用户不认识的社区名（如深圳照片命中"冈厦"而非"宝安/深圳"）；
#   4. 按 (province, city) 去重，保留 population 最大的代表点作为该市质心。
#
# admin1 码表来自 GeoNames admin1CodesASCII.txt（CN 段，已核验），稳定可复现。
# 依赖：仅标准库。用法：python3 scripts/gen_admin_centroids.py
import io
import json
import os
import urllib.request
import zipfile

OUT = "androidApp/src/main/assets/geo/admin_centroids_zh.json"
CITY_URL = "https://download.geonames.org/export/dump/cities500.zip"
# 本地缓存：开发/复跑时避免重复下载（首次下载后落盘）
CACHE = "/tmp/cities500.zip"

# 人口阈值：cities500 已是 population>=500；提高到 50000 保留县级及以上聚落。
# 实测阈值 50000 下全国约 1300 条：剔除冈厦(28600)/Huafu(2102)/Gangsha(8461) 等社区，
# 深圳坐标命中「宝安」(PPLA2 地级市府级)，质心库从 ~15326 缩至 ~1300（JSON 缩小约 12 倍）。
POP_MIN = 50000

# GeoNames CN admin1 码 → 省级行政区中文名（核验自 admin1CodesASCII.txt CN 段）
PROVINCE = {
    "01": "安徽省", "02": "浙江省", "03": "江西省", "04": "江苏省", "05": "吉林省",
    "06": "青海省", "07": "福建省", "08": "黑龙江省", "09": "河南省", "10": "河北省",
    "11": "湖南省", "12": "湖北省", "13": "新疆维吾尔自治区", "14": "西藏自治区",
    "15": "甘肃省", "16": "广西壮族自治区", "18": "贵州省", "19": "辽宁省",
    "20": "内蒙古自治区", "21": "宁夏回族自治区", "22": "北京市", "23": "上海市",
    "24": "山西省", "25": "山东省", "26": "陕西省", "28": "天津市", "29": "云南省",
    "30": "广东省", "31": "海南省", "32": "四川省", "33": "重庆市",
}


def is_cjk(s):
    return any("一" <= ch <= "鿿" for ch in s)


def pick_zh(alternatenames):
    """取 alternatenames 首个中文名;无中文返回 None（调用方跳过，避免英文/拼音地名）。"""
    for alt in alternatenames.split(","):
        alt = alt.strip()
        if alt and is_cjk(alt):
            return alt
    return None


def load_cities_bytes():
    """优先用本地缓存，避免每次重跑都重新下载。"""
    if os.path.exists(CACHE):
        with open(CACHE, "rb") as fp:
            return fp.read()
    print(f"downloading {CITY_URL} ...")
    with urllib.request.urlopen(CITY_URL, timeout=120) as r:
        data = r.read()
    with open(CACHE, "wb") as fp:
        fp.write(data)
    return data


def main():
    data = load_cities_bytes()
    best = {}  # (province, city) -> row dict
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        fname = z.namelist()[0]
        for line in io.TextIOWrapper(z.open(fname), encoding="utf-8"):
            f = line.split("\t")
            if len(f) < 15 or f[8] != "CN":
                continue
            admin1 = f[10]
            prov = PROVINCE.get(admin1)
            if not prov:
                continue
            try:
                lat = float(f[4])
                lon = float(f[5])
                pop = int(f[14]) if f[14] else 0
            except ValueError:
                continue
            if pop < POP_MIN:
                continue
            city = pick_zh(f[3])
            if city is None:
                continue  # 无中文名（英文/拼音），跳过避免低质量地名
            key = (prov, city)
            cur = best.get(key)
            if cur is None or pop > cur["pop"]:
                best[key] = {"province": prov, "city": city, "district": city,
                             "lat": lat, "lon": lon, "pop": pop}
    rows = sorted(best.values(), key=lambda r: (r["province"], -r["pop"]))
    out = [{"province": r["province"], "city": r["city"], "district": r["district"],
            "lat": r["lat"], "lon": r["lon"]} for r in rows]
    with open(OUT, "w", encoding="utf-8") as fp:
        json.dump(out, fp, ensure_ascii=False, indent=2)
    print(f"wrote {len(out)} centroids -> {OUT}")
    print("attribution: GeoNames CC-BY 4.0 — 在「关于/隐私」页署名 http://www.geonames.org")


if __name__ == "__main__":
    main()
