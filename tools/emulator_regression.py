#!/usr/bin/env python3
"""Real playback regressions on an Android emulator (never a physical device).

Requires a rooted AVD, a signed installed CastHub APK, and a local Range server
serving test.mp4 on port 8899. Discovery over physical LAN is a separate test.
"""
import argparse
import json
import plistlib
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import escape

PKG = "com.casthub.app"
AVT = "urn:schemas-upnp-org:service:AVTransport:1"


class Regression:
    def __init__(self, args):
        self.args = args
        self.out = Path(args.output)
        self.out.mkdir(parents=True, exist_ok=True)
        self.results = []
        self.logs = []
        self.dlna_port = 38080
        self.air_port = 37000

    def adb(self, *args, binary=False):
        return subprocess.check_output([self.args.adb, "-s", self.args.serial, *args],
                                       timeout=40, text=not binary, encoding=None if binary else "utf-8")

    def wait(self, fn, timeout=25):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                value = fn()
                if value:
                    return value
            except Exception:
                pass
            time.sleep(.5)
        raise AssertionError("condition timed out")

    def soap(self, action, **args):
        body = ('<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
                f'<u:{action} xmlns:u="{AVT}">' +
                ''.join(f'<{k}>{escape(str(v))}</{k}>' for k, v in {"InstanceID": "0", **args}.items()) +
                f'</u:{action}></s:Body></s:Envelope>').encode()
        req = urllib.request.Request(f"http://127.0.0.1:{self.dlna_port}/control/AVTransport",
                                     body, {"Content-Type": "text/xml", "SOAPAction": f'"{AVT}#{action}"'})
        with urllib.request.urlopen(req, timeout=5) as response:
            root = ET.fromstring(response.read())
        return {node.tag.split('}')[-1]: node.text or "" for node in root.iter()}

    def air(self, path, body=None):
        headers = {"Content-Type": "application/x-apple-binary-plist"} if body else {}
        req = urllib.request.Request(f"http://127.0.0.1:{self.air_port}{path}", body, headers)
        with urllib.request.urlopen(req, timeout=5) as response:
            raw = response.read()
        return plistlib.loads(raw) if raw else {}

    def air_play(self, url=None):
        self.air('/play', plistlib.dumps({"Content-Location": url or self.args.media, "Start-Position": 0.0}, fmt=plistlib.FMT_BINARY))

    def dlna_play(self, url=None):
        self.soap("SetAVTransportURI", CurrentURI=url or self.args.media, CurrentURIMetaData="")
        self.soap("Play", Speed="1")

    def dlna_pos(self):
        value = self.soap("GetPositionInfo")["RelTime"]
        parts = value.split(':')
        return sum(float(v) * (60 ** i) for i, v in enumerate(reversed(parts)))

    def dlna_state(self):
        return self.soap("GetTransportInfo")["CurrentTransportState"]

    def screenshot(self, name):
        (self.out / name).write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))

    def ui(self):
        self.adb("shell", "uiautomator", "dump", "/data/local/tmp/casthub-ui.xml")
        return self.adb("shell", "cat", "/data/local/tmp/casthub-ui.xml")

    def ports(self):
        log = self.adb("logcat", "-d", "-s", "DlnaModule", "AirPlayModule")
        dlna = re.findall(r'DLNA 模块已启动：.*?@[\d.]+:(\d+)', log)
        air = re.findall(r'AirPlay 已启动：.*?@[\d.]+:(\d+)', log)
        if not dlna or not air:
            return False
        self.adb("forward", f"tcp:{self.dlna_port}", f"tcp:{dlna[-1]}")
        self.adb("forward", f"tcp:{self.air_port}", f"tcp:{air[-1]}")
        self.soap("GetTransportInfo")
        self.air('/server-info')
        return True

    def restart(self):
        self.capture_log()
        self.adb("shell", "am", "force-stop", PKG)
        self.adb("logcat", "-c")
        self.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        self.wait(self.ports)
        time.sleep(1)

    def set_mode(self, mode):
        self.adb("shell", "am", "force-stop", PKG)
        path = f"/data/user/0/{PKG}/shared_prefs/casthub_modules.xml"
        raw = self.adb("shell", "cat", path)
        root = ET.fromstring(raw)
        node = next((e for e in root if e.attrib.get('name') == 'playback_mode'), None)
        if node is None:
            node = ET.SubElement(root, 'string', name='playback_mode')
        node.text = mode
        local = self.out / 'mode.xml'
        local.write_bytes(ET.tostring(root, encoding='utf-8', xml_declaration=True))
        self.adb("push", str(local), "/data/local/tmp/casthub-mode.xml")
        self.adb("shell", "cp", "/data/local/tmp/casthub-mode.xml", path)
        self.restart()

    def case(self, name, fn):
        try:
            detail = fn()
            self.results.append({"name": name, "pass": True, "detail": detail})
            print(f"PASS: {name} {detail or ''}", flush=True)
        except Exception as error:
            self.results.append({"name": name, "pass": False, "detail": str(error)})
            print(f"FAIL: {name}: {error}", flush=True)

    def playback(self):
        self.dlna_play()
        self.wait(lambda: self.dlna_state() == "PLAYING" and self.dlna_pos() >= 2)
        before = self.dlna_pos()
        time.sleep(2)
        after = self.dlna_pos()
        assert after > before
        self.screenshot('dlna-playback.png')
        return f"position {before} -> {after}s"

    def remote_pause(self):
        self.adb("shell", "input", "keyevent", "85")
        self.wait(lambda: self.dlna_state() == "PAUSED_PLAYBACK")
        before = self.dlna_pos()
        time.sleep(2)
        assert abs(self.dlna_pos() - before) <= 1
        self.adb("shell", "input", "keyevent", "85")
        self.wait(lambda: self.dlna_state() == "PLAYING")
        self.adb("shell", "input", "keyevent", "82")
        time.sleep(.8)
        ui = self.ui()
        assert "播放选项" in ui and "字幕：关闭" in ui and "音轨：自动" in ui
        self.screenshot('playback-options.png')
        self.adb("shell", "input", "keyevent", "4")

    def takeover(self):
        self.air_play()
        self.wait(lambda: self.air('/playback-info').get('position', 0) > 2)
        assert self.dlna_state() in ("STOPPED", "NO_MEDIA_PRESENT")
        self.screenshot('airplay-playback.png')
        self.dlna_play()
        self.wait(lambda: self.dlna_state() == "PLAYING")
        self.wait(lambda: self.air('/playback-info').get('position', 99) == 0)
        return "DLNA -> AirPlay -> DLNA, former stream stopped"

    def repeat_air(self):
        self.set_mode("REPEAT_ONE")
        self.air_play()
        self.wait(lambda: self.air('/playback-info').get('readyToPlay'))
        duration = self.air('/playback-info')['duration']
        self.air(f'/scrub?position={duration - 2}', b'')
        self.wait(lambda: self.air('/playback-info').get('position', 0) >= duration - 3)
        self.wait(lambda: 0.5 < self.air('/playback-info').get('position', duration) < 6, timeout=12)
        return f"replayed after reaching end of {duration}s clip"

    def repeat_dlna(self):
        self.dlna_play()
        self.wait(lambda: self.dlna_pos() > 1)
        duration = self.soap('GetPositionInfo')['TrackDuration']
        seconds = sum(float(v) * (60 ** i) for i, v in enumerate(reversed(duration.split(':'))))
        target = max(0, int(seconds - 2))
        self.soap('Seek', Unit='REL_TIME', Target=f'{target // 3600:02}:{target // 60 % 60:02}:{target % 60:02}')
        self.wait(lambda: self.dlna_pos() >= seconds - 3)
        self.wait(lambda: self.dlna_state() == 'PLAYING' and 0 < self.dlna_pos() < 6, timeout=12)

    def offline_recovery(self):
        self.soap('Stop')
        self.adb('shell', 'svc', 'wifi', 'disable')
        self.adb('shell', 'svc', 'data', 'disable')
        self.adb('shell', 'am', 'force-stop', PKG)
        self.capture_log()
        self.adb('logcat', '-c')
        self.adb('shell', 'am', 'start', '-n', f'{PKG}/.MainActivity')
        try:
            time.sleep(4)
            log = self.adb('logcat', '-d', '-s', 'DlnaModule', 'AirPlayModule')
            assert '未找到局域网' in log
        finally:
            self.adb('shell', 'svc', 'wifi', 'enable')
            self.adb('shell', 'svc', 'data', 'enable')
        self.wait(self.ports, timeout=40)
        return 'offline startup failed honestly, restored services after Wi-Fi returned'

    def run(self):
        assert self.adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Emulator only'
        assert self.adb('shell', 'id', '-u').strip() == '0', 'Run adb root for the AVD first'
        self.case('cold start and both protocol listeners', self.restart)
        self.case('DLNA real MP4 playback', self.playback)
        self.case('remote pause/resume and track menu', self.remote_pause)
        self.case('cross-protocol takeover', self.takeover)
        self.case('AirPlay repeat-one end transition', self.repeat_air)
        self.case('DLNA repeat-one', self.repeat_dlna)
        self.case('offline startup and automatic recovery', self.offline_recovery)
        self.set_mode('SINGLE')
        self.capture_log()
        log = '\n'.join(self.logs)
        (self.out / 'regression-logcat.txt').write_text(log, encoding='utf-8')
        self.case('no application crash', lambda: self.no_crash(log))
        (self.out / 'regression-results.json').write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding='utf-8')
        return 0 if all(r['pass'] for r in self.results) else 1

    @staticmethod
    def no_crash(log):
        assert 'FATAL EXCEPTION' not in log

    def capture_log(self):
        self.logs.append(self.adb('logcat', '-d', '-s', 'AndroidRuntime', 'CastHubApp', 'DlnaModule', 'AirPlayModule', 'AirPlayPlayer', 'DlnaRenderer'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default=r'D:\Android\Sdk\platform-tools\adb.exe')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--media', default='http://10.0.2.2:8899/test.mp4')
    parser.add_argument('--output', default='dist/validation')
    return Regression(parser.parse_args()).run()


if __name__ == '__main__':
    raise SystemExit(main())
