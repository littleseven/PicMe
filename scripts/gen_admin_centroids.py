#!/usr/bin/env python3
# 生成 app/src/main/assets/geo/admin_centroids_zh.json
#
# 数据来源 GeoNames (https://download.geonames.org/export/dump/) —— CC-BY 4.0，
# 须在「关于/隐私」页署名 http://www.geonames.org。
#
# 策略（稳健、可复现）：
#   1. 下载 cities500.zip，过滤 countryCode==CN；
#   2. province 用 CN admin1 省级码硬编码表（稳定）→ 中文名；
#   3. city/district 用该行 alternatenames 里的首个中文名，回退 asciiname；
#   4. 按 (province, city) 去重，保留 population 最大的代表点作为该市质心。
#
# 依赖：仅标准库。用法：python3 scripts/gen_admin_centroids.py
import csv
import io
import json
import urllib.request
import zipfile

OUT = "app/src/main/assets/geo/admin_centroids_zh.json"
CITY_URL = "https://download.geonames.org/export/dump/cities500.zip"

# GeoNames CN admin1 码 → 省级行政区中文名（稳定，官方编码）
PROVINCE = {
    "01": "北京市", "02": "天津市", "03": "河北省", "04": "山西省", "05": "内蒙古自治区",
    "06": "辽宁省", "07": "吉林省", "08": "黑龙江省", "09": "上海市", "10": "江苏省",
    "11": "浙江省", "12": "安徽省", "13": "福建省", "14": "江西省", "15": "山东省",
    "16": "河南省", "17": "湖北省", "18": "湖南省", "19": "广东省", "20": "广西壮族自治区",
    "21": "海南省", "22": "重庆市", "23": "四川省", "24": "贵州省", "25": "云南省",
    "26": "西藏自治区", "27": "陕西省", "28": "甘肃省", "29": "青海省", "30": "宁夏回族自治区",
    "31": "新疆维吾尔自治区", "32": "台湾省", "33": "香港特别行政区", "34": "澳门特别行政区",
}


def is_cjk(s):
    return any("一" <= ch <= "鿿" for ch in s)


def pick_zh(alternatenames, fallback):
    for alt in alternatenames.split(","):
        alt = alt.strip()
        if alt and is_cjk(alt):
            return alt
    return fallback


def main():
    print(f"downloading {CITY_URL} ...")
    with urllib.request.urlopen(CITY_URL, timeout=120) as r:
        data = r.read()
    best = {}  # (province, city) -> row dict
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        fname = z.namelist()[0]
        for line in io.TextIOWrapper(z.open(fname), encoding="utf-8"):
            f = line.split("\t")
            if len(f) < 15 or f[8] != "CN":
                continue
            admin1 = f[10]
            province = PROVINCE.get(admin1)
            if not province:
                continue
            try:
                lat = float(f[4])
                lon = float(f[5])
                pop = int(f[14]) if f[14] else 0
            except ValueError:
                continue
            city = pick_zh(f[3], f[1])
            key = (province, city)
            cur = best.get(key)
            if cur is None or pop > cur["pop"]:
                best[key] = {"province": province, "city": city, "district": city,
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
