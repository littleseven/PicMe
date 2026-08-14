# scripts/completeness/test_match.py
# 完整性闸门匹配器 TDD(精修版:alias + debug 过滤 + role 软匹配)
# env 无 pytest → standalone runner(python3 test_match.py)
from match import compare, NormNode


def figma(name, role="text"):
    return NormNode(label=name, role=role, x=0, y=0, w=10, h=10)


def ios(label, role="text"):
    return NormNode(label=label, role=role, x=0, y=0, w=10, h=10)


def test_missing_ios_element_is_gap():
    """Figma 有、iOS 无 = 完整性缺口(失败)。"""
    rep = compare([figma("磨皮"), figma("美白")], [ios("磨皮")])
    assert rep.missing == [NormNode("美白", "text", 0, 0, 10, 10)]
    assert rep.ok is False


def test_extra_ios_element_is_warning():
    """iOS 多(且非 debug)= 警告(不失败)。"""
    rep = compare([figma("磨皮")], [ios("磨皮"), ios("鬼影", "button")])
    assert rep.ok is True
    assert [e.label for e in rep.extra] == ["鬼影"]


def test_label_case_and_whitespace_insensitive():
    rep = compare([figma(" 磨皮 ")], [ios("磨皮")])
    assert rep.ok is True and rep.missing == []


def test_alias_maps_material_icon_to_chinese():
    """iOS 图标按钮按 Material id 标,Figma 用中文 → alias 归一后匹配。"""
    rep = compare([figma("美颜"), figma("滤镜")], [ios("mat_autofix", "button"), ios("mat_filter_b_and_w", "button")])
    assert rep.ok is True, f"应通过 alias 匹配,missing={[m.label for m in rep.missing]}"


def test_debug_overlay_filtered_from_ios():
    """iOS debug 叠加层(beauty.*/camera.fps 等)不计入 extra。"""
    rep = compare([figma("磨皮")], [ios("磨皮"), ios("beauty.eye: 0.000"), ios("camera.fps: 61"), ios("截屏", "button")])
    assert rep.ok is True
    assert rep.extra == [], f"debug 应被过滤,extra={[e.label for e in rep.extra]}"


def test_role_mismatch_is_warning_not_gap():
    """role 不同(Figma 文字占位 vs iOS button)= 匹配成功,仅 warning。"""
    rep = compare([figma("0.6x", "text")], [ios("0.6x", "button")])
    assert rep.ok is True
    assert len(rep.role_mismatch) == 1
    assert rep.missing == []


if __name__ == "__main__":
    import traceback
    _tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    _failed = 0
    for _t in _tests:
        try:
            _t()
            print(f"PASS {_t.__name__}")
        except Exception:
            _failed += 1
            print(f"FAIL {_t.__name__}")
            traceback.print_exc()
    print(f"\n{len(_tests) - _failed}/{len(_tests)} passed")
    raise SystemExit(1 if _failed else 0)
