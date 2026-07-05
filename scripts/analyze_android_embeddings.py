#!/usr/bin/env python3
"""
分析 Android 端用 RetinaFace + 2D106 + Glint360K R100 提取的真实 embedding。

输入：
- /tmp/face_embeddings.jsonl（由 adb broadcast dump_face_embeddings 导出）
- /tmp/picme_db_running.sqlite3（当前 app 数据库拷贝）

输出：
- 测试明星图同一人 / 不同人相似度分布
- DBSCAN 聚类效果（eps 扫描）
- 混淆矩阵/纯度分析
"""
import json
import re
import sqlite3
import numpy as np
from collections import defaultdict, Counter

EMB_PATH = "/tmp/face_embeddings.jsonl"
DB_PATH = "/tmp/picme_db_running.sqlite3"


def load_embeddings(path: str):
    records = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            records.append({
                "embeddingId": d["embeddingId"],
                "personId": d.get("personId") or -1,
                "mediaId": d["mediaId"],
                "embedding": np.array(d["embedding"], dtype=np.float32),
            })
    return records


def load_media_filenames(db_path: str):
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("SELECT id, fileName FROM media_assets")
    mapping = {row[0]: row[1] for row in cur.fetchall()}
    conn.close()
    return mapping


def parse_person_name(filename: str):
    m = re.search(r"TEST_PERSON_([^_]+)_", filename)
    return m.group(1) if m else None


def cosine_similarity(a: np.ndarray, b: np.ndarray):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def dbscan(embeddings: list, eps: float = 0.28, min_samples: int = 2):
    n = len(embeddings)
    labels = np.full(n, -1, dtype=int)
    visited = np.zeros(n, dtype=bool)
    cluster_id = 0

    def neighbors_of(i):
        res = []
        for j in range(n):
            if i == j:
                continue
            sim = np.dot(embeddings[i], embeddings[j])
            dist = 1.0 - sim
            if dist <= eps:
                res.append(j)
        return res

    for i in range(n):
        if visited[i]:
            continue
        visited[i] = True
        nbrs = neighbors_of(i)
        if len(nbrs) < min_samples:
            labels[i] = -1
            continue
        labels[i] = cluster_id
        seed = list(nbrs)
        idx = 0
        while idx < len(seed):
            q = seed[idx]
            if not visited[q]:
                visited[q] = True
                q_nbrs = neighbors_of(q)
                if len(q_nbrs) >= min_samples:
                    seed.extend([n for n in q_nbrs if n not in seed])
            if labels[q] == -1:
                labels[q] = cluster_id
            idx += 1
        cluster_id += 1
    return labels


def main():
    print(f"Loading embeddings from {EMB_PATH}")
    records = load_embeddings(EMB_PATH)
    print(f"Total embeddings: {len(records)}")

    print(f"Loading media filenames from {DB_PATH}")
    media_map = load_media_filenames(DB_PATH)

    # 只保留测试明星图
    test_records = []
    for r in records:
        filename = media_map.get(r["mediaId"], "")
        name = parse_person_name(filename)
        if name:
            r["name"] = name
            r["filename"] = filename
            test_records.append(r)

    print(f"Test celebrity embeddings: {len(test_records)}")
    if not test_records:
        print("No TEST_PERSON embeddings found yet.")
        return

    # 统计人数
    name_counts = Counter(r["name"] for r in test_records)
    print("\nPerson counts:")
    for name, c in name_counts.most_common():
        print(f"  {name}: {c}")

    embs = np.stack([r["embedding"] for r in test_records])
    sims = embs @ embs.T
    n = len(test_records)

    same_sims = []
    diff_sims = []
    for i in range(n):
        for j in range(i + 1, n):
            sim = sims[i, j]
            if test_records[i]["name"] == test_records[j]["name"]:
                same_sims.append(sim)
            else:
                diff_sims.append(sim)

    same_sims = np.array(same_sims)
    diff_sims = np.array(diff_sims)

    print("\n=== Cosine similarity statistics ===")
    print(f"Same-person pairs: {len(same_sims)}")
    if len(same_sims):
        print(f"  min={same_sims.min():.4f} max={same_sims.max():.4f} "
              f"mean={same_sims.mean():.4f} std={same_sims.std():.4f}")
        print(f"  p5={np.percentile(same_sims,5):.4f} p10={np.percentile(same_sims,10):.4f} "
              f"p25={np.percentile(same_sims,25):.4f} p50={np.percentile(same_sims,50):.4f}")
    print(f"Diff-person pairs: {len(diff_sims)}")
    if len(diff_sims):
        print(f"  min={diff_sims.min():.4f} max={diff_sims.max():.4f} "
              f"mean={diff_sims.mean():.4f} std={diff_sims.std():.4f}")
        print(f"  p90={np.percentile(diff_sims,90):.4f} p95={np.percentile(diff_sims,95):.4f} "
              f"p99={np.percentile(diff_sims,99):.4f}")
    if len(same_sims) and len(diff_sims):
        gap = np.percentile(same_sims, 5) - np.percentile(diff_sims, 95)
        print(f"  separation gap (same_p5 - diff_p95) = {gap:.4f}")

    # DBSCAN
    print("\n=== DBSCAN clustering ===")
    for eps in [0.20, 0.25, 0.28, 0.30, 0.35, 0.40]:
        labels = dbscan([r["embedding"] for r in test_records], eps=eps, min_samples=2)
        n_clusters = len(set(labels)) - (1 if -1 in labels else 0)
        n_noise = int((labels == -1).sum())

        clusters = defaultdict(list)
        for idx, lbl in enumerate(labels):
            clusters[lbl].append(test_records[idx]["name"])

        total_purity = 0.0
        total_size = 0
        for lbl, names in clusters.items():
            if lbl == -1:
                continue
            cnt = Counter(names)
            purity = cnt.most_common(1)[0][1] / len(names)
            total_purity += purity * len(names)
            total_size += len(names)
        avg_purity = total_purity / total_size if total_size > 0 else 0.0

        print(f"\neps={eps:.2f}: clusters={n_clusters}, noise={n_noise}/{n}, avg_purity={avg_purity:.3f}")
        for lbl in sorted(clusters.keys()):
            if lbl == -1:
                continue
            names = clusters[lbl]
            cnt = Counter(names)
            dominant, dom_count = cnt.most_common(1)[0]
            purity = dom_count / len(names)
            members_str = ", ".join(f"{k}:{v}" for k, v in sorted(cnt.items(), key=lambda x: -x[1]))
            print(f"  cluster {lbl}: size={len(names)} purity={purity:.2f} dominant={dominant} [{members_str}]")


if __name__ == "__main__":
    main()
