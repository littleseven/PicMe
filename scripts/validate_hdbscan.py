#!/usr/bin/env python3
"""
验证方案 B（k-NN 图连通分量密度自适应聚类）在 Android 真实 embedding 上的效果。

输入：
- /tmp/face_embeddings.jsonl（adb 导出）
- /tmp/picme_db_running.sqlite3（数据库拷贝）

输出：
- 各参数下的聚类数量、噪声数、平均纯度
- 与方案 A（DBSCAN）的对比
"""
import json
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
    m = __import__("re").search(r"TEST_PERSON_([^_]+)_", filename)
    return m.group(1) if m else None


def cosine_similarity(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))


def knn_connected_components(embs, k=3, min_similarity=0.40, min_cluster_size=2):
    """与 Kotlin AdaptiveFaceClusterer 对齐的 k-NN 图连通分量聚类。"""
    n = len(embs)
    if n == 0:
        return np.array([])
    if n == 1:
        return np.array([0] if min_cluster_size <= 1 else [-1])

    sims = embs @ embs.T
    sims[np.arange(n), np.arange(n)] = -1.0

    adj = defaultdict(set)
    for i in range(n):
        top_k = np.argsort(-sims[i])[:k]
        for j in top_k:
            if sims[i, j] >= min_similarity:
                adj[i].add(int(j))
                adj[j].add(i)

    visited = np.zeros(n, dtype=bool)
    labels = np.full(n, -1, dtype=int)
    lbl = 0
    for i in range(n):
        if visited[i]:
            continue
        stack = [i]
        visited[i] = True
        comp = []
        while stack:
            node = stack.pop()
            comp.append(node)
            for nb in adj[node]:
                if not visited[nb]:
                    visited[nb] = True
                    stack.append(nb)
        if len(comp) >= min_cluster_size:
            for node in comp:
                labels[node] = lbl
            lbl += 1
    return labels


def dbscan(embs, eps=0.35, min_samples=2):
    n = len(embs)
    labels = np.full(n, -1, dtype=int)
    visited = np.zeros(n, dtype=bool)
    cluster_id = 0

    def neighbors_of(i):
        res = []
        for j in range(n):
            if i == j:
                continue
            sim = np.dot(embs[i], embs[j])
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


def evaluate(records, labels):
    clusters = defaultdict(list)
    for idx, lbl in enumerate(labels):
        clusters[lbl].append(records[idx]["name"])

    n_clusters = len([k for k in clusters.keys() if k != -1])
    n_noise = int((labels == -1).sum())

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

    return n_clusters, n_noise, avg_purity


def main():
    records = load_embeddings(EMB_PATH)
    media_map = load_media_filenames(DB_PATH)

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
        print("No TEST_PERSON embeddings found.")
        return

    embs = np.stack([r["embedding"] for r in test_records])

    print("\n=== 方案 A: DBSCAN ===")
    for eps in [0.28, 0.30, 0.35, 0.40]:
        labels = dbscan(embs, eps=eps, min_samples=2)
        n_clusters, n_noise, purity = evaluate(test_records, labels)
        print(f"eps={eps:.2f}: clusters={n_clusters}, noise={n_noise}/{len(test_records)}, avg_purity={purity:.3f}")

    print("\n=== 方案 B: k-NN 图连通分量（密度自适应） ===")
    for k in [2, 3, 4, 5]:
        for min_sim in [0.30, 0.35, 0.40, 0.45, 0.50]:
            labels = knn_connected_components(embs, k=k, min_similarity=min_sim, min_cluster_size=2)
            n_clusters, n_noise, purity = evaluate(test_records, labels)
            if n_clusters >= 8:
                print(f"k={k} min_sim={min_sim:.2f}: clusters={n_clusters}, noise={n_noise}/{len(test_records)}, avg_purity={purity:.3f}")


if __name__ == "__main__":
    main()
