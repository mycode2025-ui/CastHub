#!/usr/bin/env python3
"""UI acceptance on the rooted AVD, with an independent HTTP renderer fixture."""
import json
import re
import threading
import time
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from emulator_regression import Regression, PKG
import argparse

actions = []


class Renderer(BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'''<root><device><friendlyName>Validation renderer</friendlyName>
<UDN>uuid:validation-renderer</UDN><serviceList><service>
<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
<controlURL>control</controlURL></service></serviceList></device></root>'''
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        body = self.rfile.read(int(self.headers['Content-Length'])).decode()
        action = self.headers['SOAPAction'].split('#')[-1].strip('"')
        actions.append((self.path, action, body))
        self.send_response(200)
        self.send_header('Content-Length', '0')
        self.end_headers()

    def log_message(self, *_):
        pass


class Features(Regression):
    def tap(self, text, contains=False):
        for _ in range(6):
            root = ET.fromstring(self.ui())
            nodes = [n for n in root.iter('node') if
                     (text in n.get('text', '') if contains else text == n.get('text'))]
            if nodes:
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', nodes[0].get('bounds')))
                self.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                time.sleep(.5)
                return
            self.adb('shell', 'input', 'swipe', '500', '1900', '500', '700', '350')
        raise AssertionError('UI item missing: ' + text)

    def tracks(self, protocol):
        url = 'http://10.0.2.2:8901/tracks.mp4'
        if protocol == 'DLNA':
            self.dlna_play(url)
            self.wait(lambda: self.dlna_pos() > 0)
        else:
            self.air_play(url)
            self.wait(lambda: self.air('/playback-info').get('position', 0) > 0)
        self.adb('shell', 'input', 'keyevent', '85')
        self.adb('shell', 'input', 'keyevent', '82')
        self.tap('音轨：zh', contains=True)
        self.adb('shell', 'input', 'keyevent', '82')
        assert '音轨：zh ✓' in self.ui()
        self.tap('字幕：en', contains=True)
        if protocol == 'DLNA':
            self.soap('Seek', Unit='REL_TIME', Target='00:00:00')
        else:
            self.air('/scrub?position=0', b'')
        self.adb('shell', 'input', 'keyevent', '85')
        time.sleep(1)
        self.adb('shell', 'input', 'keyevent', '85')
        self.screenshot(protocol.lower() + '-subtitles.png')
        self.adb('shell', 'input', 'keyevent', '82')
        assert '字幕：en ✓' in self.ui()
        self.tap('字幕：关闭')
        if protocol == 'DLNA':
            self.soap('Stop')
        else:
            self.air('/stop', b'')
        return 'Chinese audio and English subtitle selected; screenshot captured'

    def settings(self):
        self.restart()
        self.tap('设置', contains=True)

    def diagnostics(self):
        self.settings()
        self.tap('网络诊断 / 导出日志')
        assert '网络诊断' in self.ui()
        self.tap('导出日志')
        report = self.adb('shell', 'cat', f'/data/user/0/{PKG}/cache/diagnostics/casthub-diagnostics.txt')
        assert '1.4.3' in report and '最近日志' in report
        (self.out / 'exported-diagnostics.txt').write_text(report, encoding='utf-8')
        self.adb('shell', 'input', 'keyevent', '4')
        return 'diagnostic file exported through Android share dialog'

    def sender(self):
        self.settings()
        self.tap('投到其他设备')
        self.tap('视频地址（http / https）')
        self.adb('shell', 'input', 'text', 'http://10.0.2.2:8899/test.mp4')
        self.adb('shell', 'input', 'keyevent', '4')
        self.tap('可选：设备描述地址（device.xml）')
        self.adb('shell', 'input', 'text', 'http://10.0.2.2:8902/device.xml')
        self.adb('shell', 'input', 'keyevent', '4')
        self.tap('按设备地址连接')
        assert '已选择：Validation renderer' in self.ui()
        for label in ['开始投屏', '暂停', '继续', '停止']:
            self.tap(label)
        assert [a[1] for a in actions] == ['SetAVTransportURI', 'Play', 'Pause', 'Play', 'Stop'], actions
        assert all(a[0] == '/control' for a in actions)
        assert 'http://10.0.2.2:8899/test.mp4' in actions[0][2]
        (self.out / 'sender-actions.json').write_text(json.dumps(actions, indent=2), encoding='utf-8')
        return 'independent fixture received SetURI/Play/Pause/Play/Stop at relative controlURL'

    def external(self):
        self.adb('shell', 'pm', 'grant', 'io.github.jqssun.airplay', 'android.permission.POST_NOTIFICATIONS')
        self.settings()
        self.tap('AirPlay 镜像 / 音频（独立接收器）')
        self.tap('启动接收器')
        self.wait(lambda: re.search(r'(?:topResumedActivity|ResumedActivity):?=.*io.github.jqssun.airplay|ResumedActivity:.*io.github.jqssun.airplay', self.adb('shell', 'dumpsys', 'activity', 'activities')))
        pref = self.adb('shell', 'cat', f'/data/user/0/{PKG}/shared_prefs/casthub_modules.xml')
        assert next(n for n in ET.fromstring(pref) if n.get('name') == 'module_enabled_airplay').get('value') == 'false', pref
        self.soap('GetTransportInfo')
        self.screenshot('external-airplay-entry.png')
        return 'independent receiver launched; CastHub AirPlay disabled, DLNA reachable'

    def run(self):
        assert self.adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
        server = ThreadingHTTPServer(('0.0.0.0', 8902), Renderer)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            self.restart()
            self.case('DLNA audio and subtitle UI', lambda: self.tracks('DLNA'))
            self.case('AirPlay audio and subtitle UI', lambda: self.tracks('AirPlay'))
            self.case('diagnostic export', self.diagnostics)
            self.case('DLNA sender UI', self.sender)
            self.case('independent AirPlay entry', self.external)
            (self.out / 'feature-results.json').write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding='utf-8')
            return 0 if all(r['pass'] for r in self.results) else 1
        finally:
            server.shutdown()
            self.adb('shell', 'am', 'force-stop', 'io.github.jqssun.airplay')
            self.adb('shell', 'am', 'force-stop', PKG)
            path = f'/data/user/0/{PKG}/shared_prefs/casthub_modules.xml'
            root = ET.fromstring(self.adb('shell', 'cat', path))
            node = next((n for n in root if n.get('name') == 'module_enabled_airplay'), None)
            if node is not None:
                node.set('value', 'true')
            local = self.out / 'restored-prefs.xml'
            local.write_bytes(ET.tostring(root, encoding='utf-8'))
            self.adb('push', str(local), '/data/local/tmp/casthub-restored.xml')
            self.adb('shell', 'cp', '/data/local/tmp/casthub-restored.xml', path)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default=r'D:\Android\Sdk\platform-tools\adb.exe')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--media', default='http://10.0.2.2:8899/test.mp4')
    parser.add_argument('--output', default='dist/validation')
    raise SystemExit(Features(parser.parse_args()).run())
