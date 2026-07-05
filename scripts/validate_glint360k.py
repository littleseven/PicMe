#!/usr/bin/env python3
"""
用本地 ONNX 版 Glint360K R100 验证 Pulled 下来的明星测试图聚类效果。

流程：
1. 用 OpenCV Haar 级联检测最大人脸；
2. 按 ArcFace 标准 5 点模板做近似对齐（Haar 只能给出 bbox，因此用 bbox 中心做近似对齐，
   再resize到112x112，这对上半身/全身照是保守近似）；
3. 用 onnxruntime 跑 glintr100.onnx 提取 512 维 embedding；
4. 统计同一人 / 不同人相似度分布，并做 DBSCAN 聚类。

注：此脚本只做模型质量验证，不替代 Android 端完整的 RetinaFace+2D106 对齐链路。
"""
import os
import re
import glob
import numpy as np
import cv2
import onnxruntime as ort
from collections import defaultdict

IMAGE_DIR = "input_images/face_test"
MODEL_PATH = os.path.expanduser("~/code/antelopev2/glintr100.onnx")
INPUT_SIZE = 112
EMBEDDING_DIM = 512

# ArcFace 标准 112x112 对齐目标点（左眼/右眼/鼻尖/左嘴角/右嘴角）
DST_POINTS = np.array([
    [38.2946, 51.6963],
    [73.5318, 51.5014],
    [56.0252, 71.7366],
    [41.5493, 92.3655],
    [70.7299, 92.2041]
], dtype=np.float32)


def load_model(model_path: str):
    sess = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
    inputs = sess.get_inputs()
    outputs = sess.get_outputs()
    print(f"Model inputs:  {[i.name for i in inputs]}")
    print(f"Model outputs: {[o.name for o in outputs]}")
    return sess, inputs[0].name, outputs[0].name


def detect_face(image: np.ndarray):
    """Haar 检测最大人脸，返回 (x, y, w, h) 或 None。"""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    cascade = cv2.CascadeClassifier(
        cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
    )
    faces = cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5,
                                     minSize=(64, 64))
    if len(faces) == 0:
        return None
    # 选最大人脸
    faces = sorted(faces, key=lambda f: f[2] * f[3], reverse=True)
    return faces[0]


def bbox_to_landmarks5(x, y, w, h):
    """从 bbox 估计 5 点 landmarks（近似）。"""
    left_eye = (x + w * 0.35, y + h * 0.38)
    right_eye = (x + w * 0.65, y + h * 0.38)
    nose = (x + w * 0.50, y + h * 0.55)
    left_mouth = (x + w * 0.38, y + h * 0.75)
    right_mouth = (x + w * 0.62, y + h * 0.75)
    return np.array([left_eye, right_eye, nose, left_mouth, right_mouth],
                    dtype=np.float32)


def align_face(image: np.ndarray, src_pts: np.ndarray, size: int = 112):
    """5 点仿射对齐到 size x size。"""
    dst_pts = DST_POINTS * (size / 112.0)
    # 用前 3 点求仿射变换
    M = cv2.getAffineTransform(src_pts[:3], dst_pts[:3])
    aligned = cv2.warpAffine(image, M, (size, size),
                             borderValue=(0, 0, 0))
    return aligned


def preprocess(aligned: np.ndarray):
    """归一化到 [-1, 1]，NCHW。"""
    # aligned 是 BGR (OpenCV 读取顺序)
    img = aligned.astype(np.float32)
    img = (img - 127.5) / 128.0
    img = np.transpose(img, (2, 0, 1))  # HWC -> CHW
    img = np.expand_dims(img, axis=0)   # -> NCHW
    return img


def l2_normalize(v: np.ndarray):
    norm = np.linalg.norm(v)
    return v / norm if norm > 1e-12 else v


def extract_embedding(sess, input_name, output_name, image_path: str):
    img = cv2.imread(image_path)
    if img is None:
        print(f"Failed to read {image_path}")
        return None, None

    face = detect_face(img)
    if face is None:
        # 回退：取上 40% 中心 crop（全身照常见人脸位置）
        h, w = img.shape[:2]
        crop_h = int(h * 0.45)
        crop_y = 0
        crop_x = max(0, (w - crop_h) // 2)
        crop_w = min(w, crop_h)
        crop = img[crop_y:crop_y + crop_h, crop_x:crop_x + crop_w]
        crop = cv2.resize(crop, (INPUT_SIZE, INPUT_SIZE))
        print(f"  [fallback crop] {os.path.basename(image_path)}")
    else:
        x, y, w, h = face
        # 加 30% margin
        margin = 0.3
        x1 = max(0, int(x - w * margin))
        y1 = max(0, int(y - h * margin))
        x2 = min(img.shape[1], int(x + w * (1 + margin)))
        y2 = min(img.shape[0], int(y + h * (1 + margin)))
        crop = img[y1:y2, x1:x2]
        src_pts = bbox_to_landmarks5(x1, y1, x2 - x1, y2 - y1)
        crop = align_face(crop, src_pts, INPUT_SIZE)

    inp = preprocess(crop)
    out = sess.run([output_name], {input_name: inp})[0]
    emb = out.flatten()[:EMBEDDING_DIM]
    emb = l2_normalize(emb)
    return emb, face


def parse_person_name(filename: str):
    m = re.search(r"TEST_PERSON_([^_]+)_", filename)
    return m.group(1) if m else "unknown"


def dbscan(embeddings: list, eps: float = 0.28, min_samples: int = 2):
    """简易 DBSCAN（余弦距离）。"""
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
    print(f"Loading model: {MODEL_PATH}")
    sess, input_name, output_name = load_model(MODEL_PATH)

    image_paths = sorted(glob.glob(os.path.join(IMAGE_DIR, "TEST_PERSON_*.jpg")))
    print(f"Found {len(image_paths)} test images")

    records = []
    failed = []
    for path in image_paths:
        name = parse_person_name(os.path.basename(path))
        emb, face = extract_embedding(sess, input_name, output_name, path)
        if emb is None:
            failed.append(path)
            continue
        records.append({
            "path": path,
            "name": name,
            "embedding": emb,
            "face": face is not None
        })
        print(f"  {os.path.basename(path):50s} person={name} face_detected={face is not None}")

    print(f"\nSuccessfully extracted: {len(records)}, failed: {len(failed)}")
    if not records:
        return

    # 统计每个人图片数
    name_counts = defaultdict(int)
    for r in records:
        name_counts[r["name"]] += 1
    print("\nPerson counts:")
    for name, c in sorted(name_counts.items(), key=lambda x: -x[1]):
        print(f"  {name}: {c}")

    # 两两相似度
    embs = np.stack([r["embedding"] for r in records])
    sims = embs @ embs.T
    n = len(records)

    same_sims = []
    diff_sims = []
    for i in range(n):
        for j in range(i + 1, n):
            sim = sims[i, j]
            if records[i]["name"] == records[j]["name"]:
                same_sims.append(sim)
            else:
                diff_sims.append(sim)

    same_sims = np.array(same_sims)
    diff_sims = np.array(diff_sims)

    print("\n=== Similarity statistics ===")
    print(f"Same-person pairs: {len(same_sims)}")
    if len(same_sims):
        print(f"  min={same_sims.min():.4f} max={same_sims.max():.4f} "
              f"mean={same_sims.mean():.4f} std={same_sims.std():.4f}")
    print(f"Diff-person pairs: {len(diff_sims)}")
    if len(diff_sims):
        print(f"  min={diff_sims.min():.4f} max={diff_sims.max():.4f} "
              f"mean={diff_sims.mean():.4f} std={diff_sims.std():.4f}")
    if len(same_sims) and len(diff_sims):
        gap = same_sims.min() - diff_sims.max()
        print(f"  separation gap (same_min - diff_max) = {gap:.4f}")

    # DBSCAN 聚类验证
    for eps in [0.25, 0.28, 0.30, 0.35]:
        labels = dbscan([r["embedding"] for r in records], eps=eps, min_samples=2)
        n_clusters = len(set(labels)) - (1 if -1 in labels else 0)
        n_noise = int((labels == -1).sum())
        print(f"\nDBSCAN eps={eps:.2f}: clusters={n_clusters}, noise={n_noise}/{n}")

        # 按聚类簇统计主要成分
        clusters = defaultdict(list)
        for idx, lbl in enumerate(labels):
            clusters[lbl].append(records[idx]["name"])
        for lbl in sorted(clusters.keys()):
            names = clusters[lbl]
            from collections import Counter
            cnt = Counter(names)
            dominant = cnt.most_common(1)[0]
            purity = dominant[1] / len(names)
            print(f"  cluster {lbl}: size={len(names)} purity={purity:.2f} "
                  f"dominant={dominant[0]} names={dict(cnt)}")


if __name__ == "__main__":
    main()
