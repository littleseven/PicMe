import os
import shutil
import subprocess
import tempfile

from session import SessionManager


def _make_fake_repo(path):
    os.makedirs(path, exist_ok=True)
    subprocess.run(["git", "init", "-q"], cwd=path, check=True)
    subprocess.run(["git", "config", "user.email", "t@t"], cwd=path, check=True)
    subprocess.run(["git", "config", "user.name", "t"], cwd=path, check=True)
    with open(os.path.join(path, "README"), "w") as f:
        f.write("hi")
    subprocess.run(["git", "add", "-A"], cwd=path, check=True)
    subprocess.run(["git", "commit", "-qm", "init"], cwd=path, check=True)
    subprocess.run(["git", "branch", "-M", "main"], cwd=path, check=True)


def test_create_clones_and_branches():
    root = tempfile.mkdtemp()
    src = tempfile.mkdtemp()
    _make_fake_repo(src)
    try:
        sm = SessionManager(work_root=root, repo_url=src, base_branch="main")
        sid = sm.create()
        assert sid  # 非空
        assert sm.exists(sid)
        out = subprocess.run(["git", "-C", sm.repo_dir(sid), "rev-parse", "--abbrev-ref", "HEAD"],
                             capture_output=True, text=True, check=True)
        assert out.stdout.strip() == "claude-chat/{}".format(sid)
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(src, ignore_errors=True)


def test_claude_session_roundtrip():
    root = tempfile.mkdtemp()
    try:
        sm = SessionManager(work_root=root, repo_url="unused", base_branch="main")
        sid = "deadbeef"
        os.makedirs(sm.session_dir(sid))
        assert sm.get_claude_session(sid) is None
        sm.set_claude_session(sid, "csid-1")
        assert sm.get_claude_session(sid) == "csid-1"
    finally:
        shutil.rmtree(root, ignore_errors=True)
