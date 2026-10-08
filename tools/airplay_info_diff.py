#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/info 与 /server-info 的「本机 vs 可用设备」对照
------------------------------------------------
为什么：公告是否"自相矛盾"我们已经有自检（airplay_endpoint_conformance.py），
但那只保证**自己内部一致**，不保证**与可用设备一致**。这里拿同网内真实可用
的接收端（小米电视 / 乐播 SDK）当基准，逐字段对照，找出我们还缺什么。

    python airplay_info_diff.py --mine 192.168.10.205:7000 --ref 192.168.10.214:47828
"""
import argparse, http.client, plistlib, socket, sys
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass


def fetch(host, port, path, timeout=4.0):
    try:
        c = http.client.HTTPConnection(host, port, timeout=timeout)
        c.request("GET", path)
        r = c.getresponse()
        body = r.read()
        c.close()
    except Exception as e:
        return None, None, f"{type(e).__name__}: {e}"
    try:
        return r.status, plistlib.loads(body), None
    except Exception as e:
        return r.status, None, f"plist 解析失败 {e}: {body[:80]!r}"


def fmt(v):
    if isinstance(v, bytes):
        return f"<data {len(v)}B> {v.hex()[:24]}…"
    s = repr(v)
    return s if len(s) <= 60 else s[:57] + "…"


def compare(label, mine, ref):
    print(f"\n=== {label} ===")
    if mine is None or ref is None:
        print("  取不到，跳过")
        return
    keys = list(dict.fromkeys(list(mine.keys()) + list(ref.keys())))
    for k in sorted(keys):
        mv, rv = mine.get(k, "<缺失>"), ref.get(k, "<缺失>")
        if k in mine and k in ref and mv == rv:
            mark = "同 "
        elif k not in mine:
            mark = "缺 "
        elif k not in ref:
            mark = "多 "
        else:
            mark = "异 "
        print(f"  [{mark}] {k:<16} 本机={fmt(mv):<34} 参照={fmt(rv)}")


ap = argparse.ArgumentParser()
ap.add_argument("--mine", required=True)
ap.add_argument("--ref", required=True)
a = ap.parse_args()
mh, mp = a.mine.split(":")
rh, rp = a.ref.split(":")

for path in ("/info", "/server-info"):
    ms, mpl, merr = fetch(mh, int(mp), path)
    rs, rpl, rerr = fetch(rh, int(rp), path)
    print(f"\n########## {path} ##########")
    print(f"  本机 {a.mine}  status={ms} {('err=' + merr) if merr else ''}")
    print(f"  参照 {a.ref}  status={rs} {('err=' + rerr) if rerr else ''}")
    if mpl and rpl:
        compare(f"{path} 字段", mpl, rpl)
    elif mpl:
        print("  参照解析失败，只列本机字段：", sorted(mpl.keys()))
    elif rpl:
        print("  本机解析失败，参照字段：", sorted(rpl.keys()))
