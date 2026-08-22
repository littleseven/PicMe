#!/usr/bin/env python3
"""修复 Florence-2 merged decoder 的 use_cache_branch=True 崩溃（optimum 导出 bug）。

根因（2026-07-26 定位，spec 已随交付清理、git 历史可查）：
  merged decoder 的 If(then_branch)（decode 缓存步）里，
  `present.{L}.encoder.{key,value}`（L=0..5，共 12 个输出）被导成 **shape=(0,12,1,64) 的空 Constant**。
  decode 第 1 步用的是 prefill（else 分支）产出的真 encoder KV，所以不崩；
  第 2 步把上一步的空 present 回喂为 past → cross-attn MatMul "cannot broadcast on dim 0"。
  fp32 / INT8 / q4 三个 merged 模型 bug 完全一致（与量化无关，是 optimum traced export 的问题）。

修复（图手术）：
  cross-attn 的 K/V 来自 encoder，decode 全程不变，present 本应是 past 的直通。
  把 then_branch 里 12 个空 Constant 替换为 Identity(past_key_values.{L}.encoder.{key,value})，
  并把子图输出 value_info 形状从 else 分支同名输出拷贝过来。

用法:
  python3 scripts/florence2_fix_merged_decoder.py <in.onnx> <out.onnx>
"""
import sys
import onnx


def fix(inp: str, outp: str) -> None:
    model = onnx.load(inp)
    g = model.graph
    ifn = next(n for n in g.node if n.op_type == "If")
    attrs = {a.name: a.g for a in ifn.attribute}
    tb, eb = attrs["then_branch"], attrs["else_branch"]
    eb_out = {o.name: o for o in eb.output}

    fixed = 0
    for layer in range(6):
        for kv in ("key", "value"):
            out_name = f"present.{layer}.encoder.{kv}"
            past_name = f"past_key_values.{layer}.encoder.{kv}"
            idx = next((i for i, n in enumerate(tb.node) if out_name in n.output), None)
            if idx is None:
                raise RuntimeError(f"{out_name}: no producer in then_branch")
            prod = tb.node[idx]
            if prod.op_type != "Constant":
                raise RuntimeError(f"{out_name}: produced by {prod.op_type}, expected Constant — 模型结构变了，别硬套")
            del tb.node[idx]
            tb.node.append(onnx.helper.make_node(
                "Identity", inputs=[past_name], outputs=[out_name], name=f"fix::{out_name}"))
            # 子图输出形状声明同步成 else 分支的（正确）形状
            for o in tb.output:
                if o.name == out_name and out_name in eb_out:
                    src = eb_out[out_name]
                    o.type.CopyFrom(src.type)
            fixed += 1

    onnx.checker.check_model(model)
    onnx.save(model, outp)
    print(f"[OK] {fixed}/12 encoder present outputs rewired to Identity(past) -> {outp}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: florence2_fix_merged_decoder.py <in.onnx> <out.onnx>")
    fix(sys.argv[1], sys.argv[2])
