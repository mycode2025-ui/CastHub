#!/usr/bin/env python3
"""1.4.4 takeover, queue, presentation and real HTTP failure acceptance on an AVD."""
import argparse
import json
import re
import sys
import threading
import time
import urllib.error
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from emulator_features import Features
from emulator_regression import PKG


class FaultServer(BaseHTTPRequestHandler):
    mode = 'healthy'
    requests = []
    media = Path(r'D:\BatteryMonitor\CastHub\_testmedia\test.mp4').read_bytes()

    def log_message(self, *_):
        pass

    def do_GET(self):
        cls = type(self)
        cls.requests.append((self.path, self.headers.get('Range'), cls.mode))
        if cls.mode in ('503', '404'):
            self.send_error(int(cls.mode)); return
        size = len(cls.media)
        start = int(re.search(r'bytes=(\d+)', self.headers.get('Range', 'bytes=0-')).group(1))
        self.send_response(206 if start else 200)
        self.send_header('Content-Type', 'video/mp4')
        self.send_header('Content-Length', str(size-start))
        self.send_header('Accept-Ranges', 'bytes')
        if start:
            self.send_header('Content-Range', f'bytes {start}-{size-1}/{size}')
        self.end_headers()
        try:
            for pos in range(start, size, 16384):
                self.wfile.write(cls.media[pos:pos+16384]); self.wfile.flush()
                if cls.mode == 'cut' and pos > 1_500_000:
                    cls.mode = '503'
                    self.close_connection = True
                    self.connection.shutdown(2)
                    return
                if cls.mode == 'cut':
                    time.sleep(.04)
        except OSError:
            pass


class Acceptance(Features):
    def prefs(self, **values):
        self.capture_log()
        self.adb('shell', 'am', 'force-stop', PKG)
        path = f'/data/user/0/{PKG}/shared_prefs/casthub_modules.xml'
        root = ET.fromstring(self.adb('shell', 'cat', path))
        for key, value in values.items():
            node = next((n for n in root if n.get('name') == key), None)
            if node is not None:
                root.remove(node)
            kind = 'boolean' if isinstance(value, bool) else 'long' if key == 'subtitle_offset_ms' else 'int' if isinstance(value, int) else 'string'
            node = ET.SubElement(root, kind, name=key)
            if kind == 'string':
                node.text = value
            else:
                node.set('value', str(value).lower())
        local = self.out / 'acceptance-prefs.xml'
        local.write_bytes(ET.tostring(root, encoding='utf-8'))
        self.adb('push', str(local), '/data/local/tmp/casthub-acceptance.xml')
        self.adb('shell', 'cp', '/data/local/tmp/casthub-acceptance.xml', path)
        self.restart()

    def blocked(self):
        self.prefs(takeover_policy='BLOCK')
        self.dlna_play()
        self.wait(lambda: self.dlna_pos() > 1)
        try:
            self.air_play()
            raise AssertionError('takeover accepted')
        except urllib.error.HTTPError as e:
            assert e.code == 409
        assert self.dlna_state() == 'PLAYING'
        self.dlna_play()  # Same sender may change its own media.
        self.wait(lambda: self.dlna_state() == 'PLAYING')
        return 'new protocol rejected with HTTP 409; current sender retained control'

    def asked(self, approve):
        self.prefs(takeover_policy='ASK')
        self.dlna_play()
        try:
            self.wait(lambda: self.dlna_pos() > 1)
        except AssertionError as error:
            (self.out / 'takeover-start-timeout.log').write_text(self.adb('logcat', '-d'), encoding='utf-8')
            raise AssertionError(f'original DLNA failed to start before takeover: {self.dlna_state()} / {self.dlna_pos()}s') from error
        outcome = []
        def request():
            import plistlib, urllib.request
            req = urllib.request.Request('http://127.0.0.1:37000/play', plistlib.dumps({'Content-Location': self.args.media, 'Start-Position': 0.0}), {'Content-Type': 'application/x-apple-binary-plist'})
            try:
                with urllib.request.urlopen(req, timeout=30) as response:
                    outcome.append(response.status)
            except urllib.error.HTTPError as e:
                outcome.append(e.code)
        worker = threading.Thread(target=request); worker.start()
        self.tap('允许接管' if approve else '拒绝')
        worker.join(30)
        assert outcome == [200 if approve else 409], outcome
        if approve:
            try:
                self.wait(lambda: self.air('/playback-info').get('position', 0) > 1)
            except AssertionError as error:
                (self.out / 'takeover-approved-timeout.log').write_text(self.adb('logcat', '-d'), encoding='utf-8')
                raise AssertionError(f'approved AirPlay did not advance: {self.air("/playback-info")}') from error
            assert self.dlna_state() in ('STOPPED', 'NO_MEDIA_PRESENT')
        else:
            assert self.dlna_state() == 'PLAYING'
        return str(outcome)

    def queue_ui(self):
        self.prefs(takeover_policy='ALLOW', playback_mode='SINGLE')
        self.dlna_play()
        self.wait(lambda: self.dlna_pos() > 1)
        self.soap('Pause')
        self.adb('shell', 'input', 'keyevent', '82')
        self.tap('播放队列')
        self.tap('添加视频地址')
        self.tap('http / https 视频地址，每行一条')
        self.adb('shell', 'input', 'text', 'http://10.0.2.2:8901/tracks.mp4')
        self.adb('shell', 'input', 'keyevent', '4')
        self.tap('添加')
        assert '播放队列（2/100）' in self.ui()
        self.tap('下一集')
        self.wait(lambda: self.soap('GetPositionInfo')['TrackURI'].endswith('/tracks.mp4'))
        self.soap('Pause')
        self.tap('上一集')
        self.wait(lambda: self.soap('GetPositionInfo')['TrackURI'].endswith('/test.mp4'))
        self.soap('Pause')
        self.tap('2. 视频 2', contains=True)
        self.tap('上移')
        ui = self.ui(); assert '1. 视频 1' in ui and '▶ 2.' in ui
        self.screenshot('queue-reordered.png')
        self.tap('1. 视频 1', contains=True)
        self.tap('删除')
        assert '播放队列（1/100）' in self.ui()
        self.tap('关闭')
        return 'add, next, previous, reorder and delete; protocol URI stayed consistent'

    def picture_ui(self):
        self.prefs(picture_mode='FIT', subtitle_size=100, subtitle_bottom=8, subtitle_offset_ms=0)
        self.dlna_play()
        self.wait(lambda: self.dlna_pos() > 1)
        self.soap('Pause')
        self.adb('shell', 'input', 'keyevent', '82')
        self.tap('画面 / 字幕设置')
        self.tap('画面：', contains=True)
        self.tap('原始大小')
        self.tap('字幕字号：', contains=True)
        self.tap('150%')
        self.tap('字幕离底部：', contains=True)
        self.tap('25%')
        self.tap('字幕时间偏移：', contains=True)
        self.tap('-1.0 秒')
        self.tap('关闭')
        pref = self.adb('shell', 'cat', f'/data/user/0/{PKG}/shared_prefs/casthub_modules.xml')
        assert 'ORIGINAL' in pref and 'value="150"' in pref and 'value="-1000"' in pref
        self.screenshot('original-size.png')
        return 'picture mode and subtitle size/position/negative time offset persisted'

    def recover(self, protocol):
        self.prefs(takeover_policy='ALLOW', auto_retry=True)
        FaultServer.mode = 'cut'
        url = 'http://10.0.2.2:8903/fault.mp4'
        play = self.dlna_play if protocol == 'DLNA' else self.air_play
        play(url)
        pos = self.dlna_pos if protocol == 'DLNA' else lambda: self.air('/playback-info').get('position', 0)
        self.wait(lambda: pos() > 1, timeout=45)
        self.wait(lambda: '自动重试 1/3' in self.adb('logcat', '-d', '-s', 'PlaybackRecovery'), timeout=60)
        before = pos()
        FaultServer.mode = 'healthy'
        self.wait(lambda: pos() > before + 1, timeout=30)
        assert '投屏失败' not in self.ui()
        self.screenshot(protocol.lower() + '-recovered.png')
        return f'HTTP stream cut + 503 recovered from {before:.1f}s; new progress {pos():.1f}s'

    def exhausted(self):
        self.prefs(auto_retry=True)
        FaultServer.mode = '503'
        self.air_play('http://10.0.2.2:8903/unavailable.mp4')
        self.wait(lambda: '自动重试 3/3' in self.adb('logcat', '-d', '-s', 'PlaybackRecovery'), timeout=100)
        self.wait(lambda: '投屏失败' in self.ui(), timeout=40)
        count = len(FaultServer.requests)
        time.sleep(4)
        assert len(FaultServer.requests) == count
        log = self.adb('logcat', '-d', '-s', 'PlaybackRecovery')
        assert log.count('自动重试') == 3
        return 'exactly three application retries, then error dialog; no further HTTP loads'

    def permanent(self, enabled=True):
        self.prefs(auto_retry=enabled)
        FaultServer.mode = '404' if enabled else '503'
        self.dlna_play('http://10.0.2.2:8903/missing.mp4')
        self.wait(lambda: '投屏失败' in self.ui(), timeout=40)
        assert '自动重试' not in self.adb('logcat', '-d', '-s', 'PlaybackRecovery')
        return 'no application retries for HTTP 404' if enabled else 'retry setting disabled: HTTP 503 reported directly'

    def run(self):
        assert self.adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
        server = ThreadingHTTPServer(('0.0.0.0', 8903), FaultServer)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            self.case('block takeover and preserve sender', self.blocked)
            self.case('ask takeover rejected', lambda: self.asked(False))
            self.case('ask takeover approved', lambda: self.asked(True))
            self.case('local queue UI and SOAP consistency', self.queue_ui)
            self.case('picture and subtitle settings UI', self.picture_ui)
            self.case('DLNA stream failure recovery', lambda: self.recover('DLNA'))
            self.case('AirPlay stream failure recovery', lambda: self.recover('AirPlay'))
            self.case('bounded automatic retries', self.exhausted)
            self.case('permanent error is not retried', self.permanent)
            self.case('automatic retry can be disabled', lambda: self.permanent(False))
        finally:
            FaultServer.mode = 'healthy'
            self.prefs(takeover_policy='ALLOW', auto_retry=True, picture_mode='FIT', subtitle_size=100, subtitle_bottom=8, subtitle_offset_ms=0, playback_mode='SINGLE')
            server.shutdown()
            (self.out / 'acceptance-results.json').write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding='utf-8')
            (self.out / 'fault-requests.json').write_text(json.dumps(FaultServer.requests, indent=2), encoding='utf-8')
        return 0 if all(r['pass'] for r in self.results) else 1


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default=r'D:\Android\Sdk\platform-tools\adb.exe')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--media', default='http://10.0.2.2:8899/test.mp4')
    parser.add_argument('--output', default='dist/validation-1.4.4')
    raise SystemExit(Acceptance(parser.parse_args()).run())
