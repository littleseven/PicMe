import os

# server.py 模块级读 CT_REPO_URL（构造 SessionManager）；测试默认置一个无害值。
os.environ.setdefault("CT_REPO_URL", "unused")

import server  # noqa: E402


def test_build_cmd_includes_settings_pointing_to_existing_template():
    cmd = server.build_cmd("hi", None)
    assert "--settings" in cmd
    idx = cmd.index("--settings")
    # 指向的权限白名单模板确实随 gateway/ 落盘
    assert os.path.exists(cmd[idx + 1])


def test_build_cmd_resume_when_sid():
    cmd = server.build_cmd("hi", "csid-1")
    assert "--resume" in cmd
    assert cmd[cmd.index("--resume") + 1] == "csid-1"


def test_build_cmd_no_resume_without_sid():
    cmd = server.build_cmd("hi", None)
    assert "--resume" not in cmd


def test_build_cmd_core_flags():
    cmd = server.build_cmd("do something", None)
    assert cmd[0] == server.CLAUDE
    assert "-p" in cmd and "do something" in cmd
    assert "--output-format" in cmd
    assert "--max-turns" in cmd
