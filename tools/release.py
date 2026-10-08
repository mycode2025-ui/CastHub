#!/usr/bin/env python3
"""一条命令把当前版本发布到 GitHub 与 Gitee（源码 tag + Release + APK 附件）。

用法：
    python tools/release.py --dry-run        # 只打印将要执行的动作
    python tools/release.py                  # 真正发布
    python tools/release.py --notes docs/RELEASE_NOTES.md --skip-build

为什么要脚本化：手动发布要重复做 6 件容易出错的事（改版本号、构建、算校验和、
打 tag、推两个远端、在两站各建 Release 并上传附件），而其中任何一步漏掉或写错
（尤其是 tag 与 versionName 不一致）都会让**应用内的升级检测给出错误结论** ——
那种错在用户侧表现为"提示有新版本、装完还是旧版本"，极难排查。

Gitee 的 token 从环境变量 GITEE_TOKEN 或文件 D:/BatteryMonitor/CastHub/.gitee_token 读，
不写进任何入库文件。
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import mimetypes
import os
import re
import shutil
import subprocess
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent          # 仓库根（CastHub/CastHub）
WORKSPACE = ROOT.parent                                # 外层工作目录（放 .gitee_token）
GRADLE_FILE = ROOT / "app/build.gradle.kts"
APK_BUILT = ROOT / "app/build/outputs/apk/release/app-release.apk"
DIST = ROOT / "dist"
GH_REPO = "mycode2025-ui/CastHub"
GITEE_REPO = "mycode2025-ui/CastHub"


# ───────────────────────── 基础工具 ─────────────────────────

def run(*args: str, cwd: Path | None = None, check: bool = True) -> subprocess.CompletedProcess:
    proc = subprocess.run(
        args, cwd=str(cwd or ROOT), capture_output=True, text=True,
        encoding="utf-8", errors="ignore",
    )
    if check and proc.returncode != 0:
        print(f"  ✗ 命令失败：{' '.join(args)}")
        print((proc.stdout or "")[-800:])
        print((proc.stderr or "")[-800:])
        raise SystemExit(1)
    return proc


def read_version() -> tuple[int, str]:
    text = GRADLE_FILE.read_text(encoding="utf-8")
    code = re.search(r"^val appVersionCode = (\d+)", text, re.M)
    name = re.search(r'^val appVersionName = "([^"]+)"', text, re.M)
    if not code or not name:
        raise SystemExit("读不到 appVersionCode / appVersionName —— 版本号定义位置变了？")
    return int(code.group(1)), name.group(1)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def signer_sha256(apk: Path) -> str:
    """取签名证书指纹。用 apksigner，拿不到就返回占位符而不是编一个。"""
    adb_sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not adb_sdk:
        return "(未能读取：未设置 ANDROID_HOME)"
    apksigner = next(Path(adb_sdk, "build-tools").glob("*/apksigner.bat"), None) \
        or next(Path(adb_sdk, "build-tools").glob("*/apksigner"), None)
    if not apksigner:
        return "(未能读取：找不到 apksigner)"
    out = run(str(apksigner), "verify", "--print-certs", str(apk), check=False).stdout
    found = re.search(r"certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", out)
    return found.group(1).lower().replace(":", "") if found else "(未能读取)"


def read_gitee_token() -> str | None:
    token = os.environ.get("GITEE_TOKEN", "").strip()
    if token:
        return token
    for candidate in (WORKSPACE / ".gitee_token", ROOT / ".gitee_token"):
        if candidate.is_file():
            value = candidate.read_text(encoding="utf-8").strip()
            if value:
                return value
    return None


# ───────────────────────── Gitee API（只用标准库） ─────────────────────────

def gitee_request(
    method: str, path: str, token: str, *, body: dict | None = None,
    upload: Path | None = None,
) -> tuple[int, str]:
    boundary = uuid.uuid4().hex
    headers = {"User-Agent": "CastHub-Release-Script"}
    payload = b""

    if upload is not None:
        mime = mimetypes.guess_type(upload.name)[0] or "application/octet-stream"
        parts = [
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="access_token"\r\n\r\n{token}\r\n'.encode(),
            f"--{boundary}\r\n".encode(),
            (
                f'Content-Disposition: form-data; name="file"; filename="{upload.name}"\r\n'
                f"Content-Type: {mime}\r\n\r\n"
            ).encode(),
            upload.read_bytes(),
            f"\r\n--{boundary}--\r\n".encode(),
        ]
        payload = b"".join(parts)
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    else:
        data = dict(body or {})
        data["access_token"] = token
        payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"

    conn = http.client.HTTPSConnection("gitee.com", timeout=60)
    try:
        conn.request(method, path, body=payload, headers=headers)
        resp = conn.getresponse()
        return resp.status, resp.read().decode("utf-8", "ignore")
    finally:
        conn.close()


def gitee_find_release(token: str, tag: str) -> dict | None:
    status, text = gitee_request(
        "GET", f"/api/v5/repos/{GITEE_REPO}/releases?per_page=50&access_token={token}", token,
    )
    if status != 200 or not text.strip().startswith("["):
        return None
    for item in json.loads(text):
        if item.get("tag_name") == tag:
            return item
    return None


# ───────────────────────── 发布步骤 ─────────────────────────

def build() -> Path:
    print("① 构建 release 包")
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    run(str(gradle), "--console=plain", "assembleRelease")
    if not APK_BUILT.is_file():
        raise SystemExit(f"  构建产物不存在：{APK_BUILT}")
    return APK_BUILT


def stage(version: str) -> Path:
    print("② 归置安装包并计算校验和")
    DIST.mkdir(exist_ok=True)
    target = DIST / f"CastHub-{version}.apk"
    shutil.copyfile(APK_BUILT, target)
    print(f"  {target.name}  {target.stat().st_size:,} 字节")
    return target


def render_notes(source: Path, version: str, apk: Path, dry_run: bool) -> Path:
    """在人工撰写的说明后面补一段机器生成的校验信息。

    校验和必须由脚本算，不能让人手抄 —— 抄错等于把"校验"这件事变成误导。
    """
    print("③ 生成发布说明（附校验和）")
    if not source.is_file():
        raise SystemExit(f"  说明文件不存在：{source}")
    digest = sha256_of(apk) if not dry_run else "（dry-run）"
    signer = signer_sha256(apk) if not dry_run else "（dry-run）"
    body = source.read_text(encoding="utf-8").rstrip()
    body += (
        f"\n\n---\n\n安装包：`{apk.name}`（见下方 Assets）\n"
        f"SHA-256：`{digest}`\n"
        f"签名证书 SHA-256：`{signer}`\n"
    )
    out = DIST / "RELEASE_NOTES.final.md"
    if not dry_run:
        out.write_text(body, encoding="utf-8")
    print(f"  {out.name}")
    return out


def sync_remotes(version: str, dry_run: bool) -> list[str]:
    """推送**当前分支**与 tag 到所有远端，返回推送成功的远端。

    必须连分支一起推：只推 tag 的话，tag 会指向一个不在任何分支上的提交
    （clone 下来在 main 上根本看不到这次发布的代码）。
    **单个远端失败不中断整次发布** —— 比如 Gitee 仓库还没建好，
    不该因此让 GitHub 那边也发不出去。
    """
    tag = f"v{version}"
    branch = run("git", "rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    remotes = [r.strip() for r in run("git", "remote").stdout.split() if r.strip()]
    print(f"④ 推送分支 {branch} 与 tag {tag}")
    if not remotes:
        raise SystemExit("  没有任何远端，无法推送")
    if dry_run:
        print(f"  将推送到：{', '.join(remotes)}")
        return remotes

    head_subject = run("git", "log", "-1", "--pretty=%s").stdout.strip()
    run("git", "tag", "-f", "-a", tag, "-m", f"CastHub {version}：{head_subject}")

    pushed = []
    for remote in remotes:
        branch_proc = run("git", "push", remote, branch, check=False)
        tag_proc = run("git", "push", "-f", remote, tag, check=False)
        if branch_proc.returncode == 0 and tag_proc.returncode == 0:
            print(f"  → {remote}  ✓ 分支 + tag")
            pushed.append(remote)
        else:
            failed = branch_proc if branch_proc.returncode != 0 else tag_proc
            last = (failed.stderr or failed.stdout or "").strip().splitlines()
            print(f"  → {remote}  ✗ 失败：{last[-1] if last else '未知原因'}")
    if not pushed:
        raise SystemExit("  所有远端都推送失败")
    return pushed


def publish_github(version: str, apk: Path, notes: Path, dry_run: bool) -> bool:
    tag = f"v{version}"
    title = f"CastHub {version}"
    print(f"⑤ GitHub Release（{GH_REPO}）")
    if dry_run:
        print(f"  将创建/更新 Release {tag} 并上传 {apk.name}")
        return True
    exists = run("gh", "release", "view", tag, "--repo", GH_REPO,
                 "--json", "tagName", check=False)
    if exists.returncode == 0:
        # 注意：不要用"删 tag 重建"来改指向 —— GitHub 会把对应 Release 变成
        # 未发布的草稿（draft=true），而草稿对匿名接口不可见，应用会看到"零个 Release"
        run("gh", "release", "edit", tag, "--repo", GH_REPO,
            "--title", title, "--notes-file", str(notes), "--draft=false", "--tag", tag)
        run("gh", "release", "upload", tag, str(apk), "--repo", GH_REPO, "--clobber")
        print("  已更新既有 Release")
    else:
        proc = run("gh", "release", "create", tag, str(apk), "--repo", GH_REPO,
                   "--title", title, "--notes-file", str(notes), check=False)
        if proc.returncode != 0:
            print("  ✗", (proc.stderr or proc.stdout or "").strip()[:300])
            return False
        print("  已创建 Release")
    return True


def publish_gitee(version: str, apk: Path, notes: Path, dry_run: bool) -> bool:
    """@return 是否成功发布。拿不到令牌时返回 False 而不是中断 ——
    GitHub 那边可能已经发好了，把整次发布判为失败会掩盖这个事实。"""
    tag = f"v{version}"
    print(f"⑥ Gitee Release（{GITEE_REPO}）")
    token = read_gitee_token()
    if not token:
        print("  ✗ 找不到 Gitee 私人令牌，跳过。")
        print(f"    从 Gitee「设置 → 安全设置 → 私人令牌」生成（需 projects 权限），")
        print(f"    写入 {WORKSPACE / '.gitee_token'}，或设为环境变量 GITEE_TOKEN。")
        return False
    if dry_run:
        print(f"  将创建/更新 Release {tag} 并上传 {apk.name}")
        return True

    body = notes.read_text(encoding="utf-8")
    existing = gitee_find_release(token, tag)
    if existing:
        release_id = existing["id"]
        status, text = gitee_request(
            "PATCH", f"/api/v5/repos/{GITEE_REPO}/releases/{release_id}", token,
            body={"tag_name": tag, "name": f"CastHub {version}", "body": body, "prerelease": False},
        )
        print(f"  更新既有 Release（HTTP {status}）")
    else:
        status, text = gitee_request(
            "POST", f"/api/v5/repos/{GITEE_REPO}/releases", token,
            body={
                "tag_name": tag,
                "target_commitish": run("git", "rev-parse", "HEAD").stdout.strip(),
                "name": f"CastHub {version}",
                "body": body,
                "prerelease": False,
            },
        )
        print(f"  创建 Release（HTTP {status}）")
        if status not in (200, 201):
            print("  ✗", text[:300])
            return False
        release_id = json.loads(text)["id"]

    status, text = gitee_request(
        "POST", f"/api/v5/repos/{GITEE_REPO}/releases/{release_id}/attach_files",
        token, upload=apk,
    )
    print(f"  上传 {apk.name}（HTTP {status}）")
    if status not in (200, 201):
        print("  ✗", text[:300])
        return False
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="发布当前版本到 GitHub 与 Gitee")
    parser.add_argument("--dry-run", action="store_true", help="只打印将执行的动作")
    parser.add_argument("--notes", default="docs/RELEASE_NOTES.md", help="人工撰写的发布说明")
    parser.add_argument("--skip-build", action="store_true", help="复用已有构建产物")
    args = parser.parse_args()

    code, version = read_version()
    tag = f"v{version}"
    print(f"CastHub {version}（versionCode {code}）→ tag {tag}")
    print()

    if args.dry_run:
        # dry-run 只报告计划，不构建也不落地任何文件
        print("① 构建 release 包（dry-run 跳过）")
        print("② 归置安装包并计算校验和（dry-run 跳过）")
        apk = DIST / f"CastHub-{version}.apk"
    else:
        if not args.skip_build:
            build()
        if not APK_BUILT.is_file():
            raise SystemExit(f"找不到构建产物：{APK_BUILT}")
        apk = stage(version)

    notes = render_notes(ROOT / args.notes, version, apk, args.dry_run)
    sync_remotes(version, args.dry_run)
    github_ok = publish_github(version, apk, notes, args.dry_run)
    gitee_ok = publish_gitee(version, apk, notes, args.dry_run)

    print()
    if args.dry_run:
        print("（dry-run，未做任何改动）")
        return 0
    print(f"汇总：GitHub {'✅' if github_ok else '❌'}　Gitee {'✅' if gitee_ok else '⏭ 跳过'}")
    if not github_ok:
        return 1
    if not gitee_ok:
        print("（Gitee 未发布不影响 GitHub 侧；补上令牌后重跑本脚本即可，不会重复发布）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
