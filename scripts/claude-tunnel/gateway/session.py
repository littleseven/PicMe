"""Session/workdir 管理：一个 app 会话 → 一个 workdir + git 分支 + claude session_id。"""
import os
import subprocess
import uuid


class SessionManager:
    def __init__(self, work_root, repo_url, base_branch="main"):
        self.work_root = work_root
        self.repo_url = repo_url
        self.base_branch = base_branch

    def session_dir(self, sid):
        return os.path.join(self.work_root, sid)

    def repo_dir(self, sid):
        return os.path.join(self.session_dir(sid), "repo")

    def exists(self, sid):
        return os.path.isdir(os.path.join(self.repo_dir(sid), ".git"))

    def create(self):
        """新会话：生成 sid，clone 仓 + checkout claude-chat/<sid>。返回 sid。"""
        sid = uuid.uuid4().hex[:12]
        os.makedirs(self.session_dir(sid), exist_ok=True)
        repo = self.repo_dir(sid)
        subprocess.run(["git", "clone", "--quiet", self.repo_url, repo], check=True)
        subprocess.run(["git", "-C", repo, "checkout", "--quiet",
                        "-B", "claude-chat/{}".format(sid), self.base_branch], check=True)
        subprocess.run(["git", "-C", repo, "config", "user.email", "claude-tunnel@polang"], check=False)
        subprocess.run(["git", "-C", repo, "config", "user.name", "claude-tunnel"], check=False)
        return sid

    def _claude_session_file(self, sid):
        return os.path.join(self.session_dir(sid), ".claude_session")

    def get_claude_session(self, sid):
        f = self._claude_session_file(sid)
        if os.path.exists(f):
            with open(f) as fh:
                return fh.read().strip()
        return None

    def set_claude_session(self, sid, csid):
        with open(self._claude_session_file(sid), "w") as fh:
            fh.write(csid)
