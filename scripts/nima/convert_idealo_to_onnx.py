#!/usr/bin/env python3
"""把 idealo MobileNet(V1) aesthetic 权重转成 ONNX。

源：weights_mobilenet_aesthetic_0.07.hdf5（Keras 2.1.6 weights-only）
架构：MobileNet(include_top=False, pooling='avg', 224×224×3) → Dropout(0.75) → Dense(10, softmax)
输入 NHWC [-1,1]；输出 softmax 10-bin，score=Σ p_i·(i+1) ∈[1,10]。
"""
import os, sys
os.environ.setdefault("TF_USE_LEGACY_KERAS", "1")   # 让 tensorflow 用 tf_keras(Keras2)
import h5py
import numpy as np
import tf_keras as keras
from tf_keras.applications import MobileNet
from tf_keras.layers import Dropout, Dense
from tf_keras.models import Model

H5 = "/tmp/nima_verify/idealo/weights_mobilenet_aesthetic_0.07.hdf5"
SM = "/tmp/nima_verify/idealo/nima_savedmodel"
ONNX = "/tmp/nima_verify/idealo/nima_mobilenet_aesthetic_idealo.onnx"


def build():
    base = MobileNet(input_shape=(224, 224, 3), include_top=False, weights=None, pooling="avg")
    x = Dropout(0.75, name="dropout_1")(base.output)
    out = Dense(10, activation="softmax", name="dense_1")(x)
    m = Model(base.input, out, name="nima_mobilenet_aesthetic")
    return m, base


def load_weights(model):
    # 优先按拓扑位置整体载入
    try:
        model.load_weights(H5)
        print("[load] positional load_weights OK")
    except Exception as e:
        print("[load] positional failed:", str(e)[:160])
        print("[load] fallback: by_name + skip_mismatch + 手动 dense")
        model.load_weights(H5, by_name=True, skip_mismatch=True)
        # 手动补 dense_1
        with h5py.File(H5, "r") as h:
            g = h["dense_1"]
            k = np.array(g["kernel:0"]); b = np.array(g["bias:0"])
        dl = model.get_layer("dense_1")
        dl.set_weights([k, b])
        print("[load] dense_1 manually set")


def validate_load(model):
    """抽 conv1 kernel 与 dense kernel 比对源文件，确认非随机。
    Keras HDF5 权重为嵌套：layer/layer/weight。"""
    def src(layer, w):
        with h5py.File(H5, "r") as h:
            return np.array(h[layer][layer][w])
    src_conv1 = src("conv1", "kernel:0")
    src_dense = src("dense_1", "kernel:0")
    got_conv1 = model.get_layer("conv1").get_weights()[0]
    got_dense = model.get_layer("dense_1").get_weights()[0]
    ok1 = src_conv1.shape == got_conv1.shape and np.allclose(src_conv1, got_conv1)
    ok2 = src_dense.shape == got_dense.shape and np.allclose(src_dense, got_dense)
    print(f"[validate] conv1 match={ok1} dense match={ok2}")
    return ok1 and ok2


def sanity(model):
    from PIL import Image
    img = Image.open("input_images/face.jpg").convert("RGB").resize((224, 224), Image.BILINEAR)
    x = (np.asarray(img, dtype=np.float32) / 127.5 - 1.0)[None]
    out = model.predict(x, verbose=0)[0]
    print(f"[sanity] face.jpg score={np.sum(out*np.arange(1,11)):.3f} argmax={int(np.argmax(out))+1} sum={out.sum():.3f}")


def main():
    model, base = build()
    print(f"[build] total params={model.count_params():,} layers={len(model.layers)}")
    load_weights(model)
    if not validate_load(model):
        print("!! 权重校验失败，中止"); sys.exit(1)
    sanity(model)

    # 存 SavedModel，再 tf2onnx
    model.save(SM)
    print(f"[save] SavedModel -> {SM}")
    import tf2onnx
    spec = (tf2onnx.TFGraphBuilder if False else None)  # 占位
    # 用命令行子进程转最稳
    import subprocess
    cmd = [sys.executable, "-m", "tf2onnx.convert", "--saved-model", SM,
           "--output", ONNX, "--opset", "13"]
    print("[onnx]", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    print(r.stdout[-1500:])
    if r.returncode != 0:
        print("STDERR:", r.stderr[-1500:]); sys.exit(1)
    print(f"[done] {ONNX} ({os.path.getsize(ONNX)//1024} KB)")


if __name__ == "__main__":
    main()
