# scripts/completeness/test_match.py
# 完整性闸门匹配器 TDD:Figma node 列表 vs iOS a11y 列表 → 缺口报告
# 注:env 无 pytest,用 standalone runner(python3 test_match.py);pytest 可用时也可发现。
from match import compare, NormNode


def figma(name, role="text"):
    return NormNode(label=name, role=role, x=0, y=0, w=10, h=10)


def ios(label, role="text"):
    return NormNode(label=label, role=role, x=0, y=0, w=10, h=10)


def test_missing_ios_element_is_gap():
    """Figma 有、iOS 无 = 完整性缺口(失败)。"""
    rep = compare([figma("磨皮"), figma("美白")], [ios("磨皮")])
    assert rep.missing == [NormNode("美白", "text", 0, 0, 10, 10)]
    assert rep.extra == []
    assert rep.ok is False


def test_extra_ios_element_is_warning():
    """iOS 多出来 = 警告(不失败)。"""
    rep = compare([figma("磨皮")], [ios("磨皮"), ios("鬼影", "button")])
    assert rep.missing == []
    assert rep.extra == [NormNode("鬼影", "button", 0, 0, 10, 10)]
    assert rep.ok is True


def test_label_case_and_whitespace_insensitive():
    """label 去空白+小写后匹配(两端格式可能不同)。"""
    rep = compare([figma(" 磨皮 ")], [ios("磨皮")])
    assert rep.ok is True and rep.missing == []


def test_role_mismatch_is_gap():
    """label 同但 role 不同(如 slider vs button)= 缺口。"""
    rep = compare([figma("曝光", "slider")], [ios("曝光", "button")])
    assert rep.ok is False
    assert len(rep.missing) == 1


# standalone runner(env 无 pytest 时用 `python3 test_match.py`)
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
