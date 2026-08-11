#!/usr/bin/env python3
"""
iOS 人脸 embedding pair 相似度诊断(Step1 验收)。
用法:
  1. 扫描后从设备拉 TagDatabase sqlite:
     xcrun devicectl device copy from --device <UDID> \
       --source .../Documents/TagDatabase.sqlite --output ./Tag.sqlite
  2. 准备分组:从 app 人物页肉眼确认哪几个 mediaId 是同一人,写进 GROUPS。
  3. python3 scripts/ios_face_sim_diag.py ./Tag.sqlite
"""
import sqlite3
import struct
import sys
from itertools import combinations
from statistics import median

GROUPS = {
    # "person_a": [<mediaId>, <mediaId>, ...],   # 改成实测 mediaId
    # "person_b": [<mediaId>, ...],
}


def load_embeddings(db_path):
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    # 列名按 TagDatabase 实际 schema;若不符,先 sqlite3 .schema face_embeddings 核对
    cur.execute("SELECT media_id, embedding FROM face_embeddings")
    out = {}
    for media_id, blob in cur.fetchall():
        if blob is None or len(blob) != 512 * 4:
            continue
        vec = struct.unpack("<512f", blob)
        out.setdefault(media_id, []).append(vec)
    conn.close()
    return out  # media_id -> [embedding,...] (一张图可能多脸)


def cos(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = sum(x * x for x in a) ** 0.5
    nb = sum(y * y for y in b) ** 0.5
    return dot / (na * nb) if na and nb else 0.0


def pct(xs, p):
    if not xs:
        return float("nan")
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p))]


def main(db_path):
    embs = load_embeddings(db_path)
    # same-person:同组内任意 media_id 的任意 embedding 两两
    same = []
    for person, mids in GROUPS.items():
        vecs = []
        for m in mids:
            vecs.extend(embs.get(m, []))
        for a, b in combinations(vecs, 2):
            same.append(cos(a, b))
    # cross-person:不同组间
    cross = []
    persons = list(GROUPS.items())
    for i in range(len(persons)):
        for j in range(i + 1, len(persons)):
            va = []
            for m in persons[i][1]:
                va.extend(embs.get(m, []))
            vb = []
            for m in persons[j][1]:
                vb.extend(embs.get(m, []))
            for a in va:
                for b in vb:
                    cross.append(cos(a, b))

    print(f"same-person  n={len(same)}  median={median(same) if same else float('nan'):.3f}  "
          f"P10={pct(same, 0.1):.3f}  P50={pct(same, 0.5):.3f}  P90={pct(same, 0.9):.3f}")
    print(f"cross-person n={len(cross)} median={median(cross) if cross else float('nan'):.3f}  "
          f"P10={pct(cross, 0.1):.3f}  P50={pct(cross, 0.5):.3f}  P90={pct(cross, 0.9):.3f}")
    print(f"gap(same P50 - cross P50) = {(pct(same, 0.5) - pct(cross, 0.5)):.3f}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "Tag.sqlite")
