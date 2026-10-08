#!/usr/bin/env python3
"""Seven-feature acceptance with real players in a rooted Android AVD."""
import argparse
import hashlib
import json
import re
import sys
import threading
import time
import traceback
import urllib.error
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from emulator_144 import Acceptance
from emulator_regression import PKG


class Drip(BaseHTTPRequestHandler):
    healthy = False
    media = Path(r'D:\BatteryMonitor\CastHub\_testmedia\test.mp4').read_bytes()
    def log_message(self, *_): pass
    def do_GET(self):
        data = self.media
        start = int(re.search(r'bytes=(\d+)', self.headers.get('Range', 'bytes=0-')).group(1))
        self.send_response(206 if start else 200)
        self.send_header('Content-Type', 'video/mp4')
        self.send_header('Content-Length', str(len(data)-start))
        self.send_header('Accept-Ranges', 'bytes')
        if start: self.send_header('Content-Range', f'bytes {start}-{len(data)-1}/{len(data)}')
        self.end_headers()
        try:
            if type(self).healthy: self.wfile.write(data[start:]); return
            for pos in range(start, len(data)):
                self.wfile.write(data[pos:pos+1]); self.wfile.flush()
                time.sleep(1)
                if type(self).healthy: self.wfile.write(data[pos+1:]); return
        except OSError: pass


class NewAcceptance(Acceptance):
    def case(self, name, fn):
        def traced():
            try: return fn()
            except Exception:
                traceback.print_exc()
                raise
        super().case(name, traced)
    def log(self): return self.adb('logcat', '-d', '-s', 'PlaybackWatchdog', 'PlaybackRecovery', 'AirPlayPlayer', 'DlnaRenderer')
    def clear_history_ui(self):
        self.tap('设置'); self.tap('播放与接管设置'); self.tap('查看 / 清空播放历史')
        self.tap('清空记录'); self.tap('清空'); assert '暂无记录' in self.ui(); self.restart()
    def menu(self, text):
        self.adb('shell', 'input', 'keyevent', '82'); self.tap(text)
    def repeated_takeover(self):
        self.prefs(history_enabled=False, takeover_policy='ALLOW', playback_mode='SINGLE')
        for _ in range(6):
            self.air_play()
            self.wait(lambda: self.air('/playback-info').get('position', 0) > 1)
            self.dlna_play()
            self.wait(lambda: self.dlna_pos() >= 3)
            assert self.dlna_state() == 'PLAYING'
        self.soap('Stop')
        def inactive():
            found = re.search(r'CastHub com\.casthub\.app/CastHub.*?active=(true|false)',
                self.adb('shell', 'dumpsys', 'media_session'), re.S)
            return found and found.group(1) == 'false'
        self.wait(inactive)
        return 'six AirPlay to DLNA transitions remained playing beyond delayed SystemUI stop; real stop deactivated session'
    def stall(self, protocol):
        self.prefs(history_enabled=False, auto_retry=True, stall_recovery=True, takeover_policy='ALLOW')
        Drip.healthy = False
        (self.dlna_play if protocol == 'DLNA' else self.air_play)('http://10.0.2.2:8904/drip.mp4')
        self.wait(lambda: 'PlaybackWatchdog' in self.log() and '自动重试 1/3' in self.log(), timeout=43)
        Drip.healthy = True
        pos = self.dlna_pos if protocol == 'DLNA' else lambda: self.air('/playback-info').get('position', 0)
        self.wait(lambda: pos() > 1, timeout=18)
        assert '自动重试 2/3' not in self.log()
        (self.out / f'{protocol.lower()}-watchdog.log').write_text(self.log(), encoding='utf-8')
        return 'alive HTTP byte drip stalled >30s; watchdog retry recovered actual playback'
    def pause_not_stall(self):
        self.prefs(history_enabled=False, stall_recovery=True)
        self.dlna_play(); self.wait(lambda: self.dlna_pos() > 1)
        self.soap('Pause'); before = self.dlna_pos(); time.sleep(32)
        assert self.dlna_state() == 'PAUSED_PLAYBACK' and abs(self.dlna_pos()-before) <= 1
        assert 'PlaybackWatchdog' not in self.log()
        return 'paused for 32s without watchdog or unintended resume'
    def transport(self):
        self.prefs(history_enabled=False, playback_mode='SINGLE')
        self.dlna_play(); self.wait(lambda: self.dlna_pos() > 1)
        self.soap('SetNextAVTransportURI', NextURI='http://10.0.2.2:8901/tracks.mp4', NextURIMetaData='')
        actions = self.soap('GetCurrentTransportActions')['Actions']
        assert all(a in actions for a in ['Pause', 'Next', 'Seek']), actions
        for mode in ['REPEAT_ONE', 'REPEAT_ALL', 'NORMAL']:
            self.soap('SetPlayMode', NewPlayMode=mode)
            assert self.soap('GetTransportSettings')['PlayMode'] == mode
        try: self.soap('SetPlayMode', NewPlayMode='SHUFFLE'); raise AssertionError('invalid mode accepted')
        except urllib.error.HTTPError as e: assert '712' in e.read().decode()
        self.soap('Next'); self.wait(lambda: self.soap('GetPositionInfo')['TrackURI'].endswith('tracks.mp4'))
        self.soap('Previous'); self.wait(lambda: self.soap('GetPositionInfo')['TrackURI'].endswith('test.mp4'))
        self.soap('Pause'); self.wait(lambda: 'Play' in self.soap('GetCurrentTransportActions')['Actions'])
        return 'SOAP actions/modes, Next/Previous and unsupported-mode fault 712 verified'
    def media_session(self):
        self.prefs(history_enabled=False, playback_mode='SINGLE')
        self.air_play(); self.wait(lambda: self.air('/playback-info').get('position', 0) > 1)
        self.adb('shell', 'input', 'keyevent', '3')
        self.adb('shell', 'cmd', 'media_session', 'dispatch', 'pause')
        self.wait(lambda: self.air('/playback-info')['rate'] == 0)
        before = self.air('/playback-info')['position']; time.sleep(2)
        assert abs(self.air('/playback-info')['position']-before) < .8
        self.adb('shell', 'cmd', 'media_session', 'dispatch', 'play')
        self.wait(lambda: self.air('/playback-info')['position'] > before+1)
        dump = self.adb('shell', 'dumpsys', 'media_session'); assert 'CastHub' in dump
        notices = self.adb('shell', 'dumpsys', 'notification', '--noredact')
        (self.out/'media-session.txt').write_text(dump, encoding='utf-8')
        (self.out/'notification.txt').write_text(notices, encoding='utf-8')
        assert 'android.app.Notification$MediaStyle' in notices and '"暂停"' in notices and '"结束投屏"' in notices
        self.adb('shell', 'cmd', 'media_session', 'dispatch', 'stop')
        self.wait(lambda: self.air('/playback-info')['position'] == 0); self.restart()
        return 'background system keys pause/play/stop real AirPlay player; MediaStyle notification verified'
    def quality(self):
        self.prefs(history_enabled=False)
        self.dlna_play(); self.wait(lambda: self.dlna_pos() > 1)
        self.menu('播放质量诊断'); raw = self.ui()
        for label in ['640 × 360', 'video/avc', '解码器：', '可用缓冲：', '丢帧：', '应用重试：']: assert label in raw, label
        self.screenshot('quality-diagnostics.png'); self.tap('关闭')
        return 'real H264 dimensions, decoder, buffering and retry diagnostics shown'
    def track_memory(self):
        self.prefs(history_enabled=False, audio_language='', text_language='', subtitles_enabled=True)
        self.dlna_play('http://10.0.2.2:8901/tracks.mp4'); self.wait(lambda: self.dlna_pos() > 0)
        self.adb('shell', 'input', 'keyevent', '82'); self.tap('音轨：zh', contains=True)
        self.adb('shell', 'input', 'keyevent', '82'); self.tap('字幕：en', contains=True)
        self.soap('Stop'); self.restart()
        self.air_play('http://10.0.2.2:8901/tracks.mp4'); self.wait(lambda: self.air('/playback-info').get('position', 0) > 0)
        self.adb('shell', 'input', 'keyevent', '82'); raw = self.ui()
        assert '音轨：zh ✓' in raw and '字幕：en ✓' in raw, raw
        self.tap('字幕：关闭'); self.air('/stop', b''); self.restart()
        self.dlna_play('http://10.0.2.2:8901/tracks.mp4'); self.wait(lambda: self.dlna_pos() > 0)
        self.adb('shell', 'input', 'keyevent', '82'); assert '字幕：en ✓' not in self.ui(); self.tap('关闭')
        return 'language choices survived restart/protocol switch; subtitle-off remembered'
    def external(self, protocol, extension):
        self.prefs(history_enabled=False, subtitle_offset_ms=0)
        (self.dlna_play if protocol == 'DLNA' else self.air_play)()
        pos = self.dlna_pos if protocol == 'DLNA' else lambda: self.air('/playback-info').get('position', 0)
        self.wait(lambda: pos() > 1)
        if protocol == 'DLNA': self.soap('Pause')
        else: self.air('/rate?value=0', b'')
        before = pos()
        self.menu('外部字幕'); self.tap('输入 SRT / VTT 地址')
        self.adb('shell', 'input', 'text', f'http://10.0.2.2:8901/external.{extension}'); self.tap('加载'); time.sleep(1)
        self.adb('shell', 'input', 'keyevent', '82'); self.tap('字幕：外部字幕', contains=True)
        assert abs(pos()-before) < 1.5, (before, pos())
        self.adb('logcat', '-c')
        if protocol == 'DLNA': self.soap('Seek', Unit='REL_TIME', Target='00:00:02'); self.soap('Play', Speed='1')
        else: self.air('/scrub?position=2', b''); self.air('/rate?value=1', b'')
        tag = 'DlnaRenderer' if protocol == 'DLNA' else 'AirPlayPlayer'
        self.wait(lambda: '字幕更新：1' in self.adb('logcat', '-d', '-s', tag), timeout=8)
        self.screenshot(f'{protocol.lower()}-external-{extension}.png')
        self.menu('外部字幕'); self.tap('移除外部字幕')
        return f'{extension} rendered then removed on {protocol}; pause position preserved'
    def history_resume(self, protocol):
        self.prefs(history_enabled=True, playback_mode='SINGLE'); self.clear_history_ui()
        pos = self.dlna_pos if protocol == 'DLNA' else lambda: self.air('/playback-info').get('position', 0)
        play = self.dlna_play if protocol == 'DLNA' else self.air_play
        play(); self.wait(lambda: pos() > 1)
        if protocol == 'DLNA': self.soap('Seek', Unit='REL_TIME', Target='00:00:12'); self.soap('Pause')
        else: self.air('/scrub?position=12', b''); self.air('/rate?value=0', b'')
        self.wait(lambda: pos() >= 12)
        if protocol == 'DLNA': self.soap('Stop')
        else: self.air('/stop', b'')
        self.restart(); play(); self.wait(lambda: '继续上次播放？' in self.ui(), timeout=15)
        self.screenshot(f'{protocol.lower()}-resume-prompt.png'); self.tap('继续播放'); self.wait(lambda: pos() >= 12)
        raw = self.adb('shell', 'cat', f'/data/user/0/{PKG}/shared_prefs/casthub_history.xml')
        assert 'http://' not in raw and hashlib.sha256(self.args.media.encode()).hexdigest() in raw
        if protocol == 'DLNA': self.soap('Stop')
        else: self.air('/stop', b'')
        self.clear_history_ui()
        return '12s persisted across restart and resumed; hashed history and UI clear verified'
    def local_subtitle(self):
        self.prefs(history_enabled=False, subtitle_offset_ms=0)
        self.adb('push', 'dist/validation/external.srt', '/sdcard/Download/casthub-external.srt')
        self.dlna_play(); self.wait(lambda: self.dlna_pos() > 1); self.soap('Pause')
        self.menu('外部字幕'); self.tap('选择本地字幕文件')
        raw = self.ui()
        if 'casthub-external.srt' not in raw:
            node = next(n for n in ET.fromstring(raw).iter('node') if n.get('content-desc') == 'Show roots')
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            self.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)); self.tap('Downloads')
        self.tap('casthub-external.srt')
        self.adb('shell', 'input', 'keyevent', '82'); self.tap('字幕：外部字幕', contains=True)
        self.adb('logcat', '-c'); self.soap('Seek', Unit='REL_TIME', Target='00:00:02'); self.soap('Play', Speed='1')
        self.wait(lambda: '字幕更新：1' in self.adb('logcat', '-d', '-s', 'DlnaRenderer'))
        self.screenshot('local-srt.png'); self.soap('Stop')
        return 'system document picker content URI rendered actual SRT captions'
    def pause_pending(self):
        self.prefs(history_enabled=False, auto_retry=True, stall_recovery=True)
        Drip.healthy = False; self.air_play('http://10.0.2.2:8904/drip.mp4')
        self.wait(lambda: '自动重试 1/3' in self.log(), timeout=43)
        self.air('/rate?value=0', b''); Drip.healthy = True
        self.wait(lambda: self.air('/playback-info').get('readyToPlay'), timeout=18)
        before = self.air('/playback-info')['position']; time.sleep(2)
        info = self.air('/playback-info')
        assert info['rate'] == 0 and abs(info['position']-before) < .8, info
        return 'pause during watchdog retry delay preserved after prepare recovered'
    def explicit_start(self):
        import plistlib
        self.prefs(history_enabled=True); self.clear_history_ui()
        self.air_play(); self.wait(lambda: self.air('/playback-info').get('position', 0) > 1)
        self.air('/scrub?position=10', b''); self.air('/rate?value=0', b''); self.air('/stop', b''); self.restart()
        self.air('/play', plistlib.dumps({'Content-Location': self.args.media, 'Start-Position': .5}, fmt=plistlib.FMT_BINARY))
        self.wait(lambda: self.air('/playback-info').get('position', 0) >= 15)
        assert '继续上次播放？' not in self.ui()
        self.air('/stop', b''); self.clear_history_ui()
        return 'sender explicit 50 percent start took priority over saved history without resume prompt'
    def bridge(self):
        self.prefs(history_enabled=False, module_enabled_airplay=True)
        self.tap('设置'); self.tap('AirPlay 镜像 / 音频', contains=True); self.tap('启动接收器')
        self.wait(lambda: re.search(r'topResumedActivity=.*io.github.jqssun.airplay', self.adb('shell', 'dumpsys', 'activity', 'activities')))
        modules = self.adb('shell', 'cat', f'/data/user/0/{PKG}/shared_prefs/casthub_modules.xml')
        assert 'name="module_enabled_airplay" value="false"' in modules, modules
        self.adb('shell', 'am', 'force-stop', 'io.github.jqssun.airplay')
        self.adb('shell', 'am', 'start', '-n', f'{PKG}/.MainActivity')
        self.wait(lambda: '恢复 CastHub AirPlay 视频？' in self.ui())
        self.tap('已关闭，恢复视频'); self.wait(self.ports)
        self.wait(lambda: 'restore_pending' not in self.adb('shell', 'cat', f'/data/user/0/{PKG}/shared_prefs/airplay_bridge.xml'))
        bridge = self.adb('shell', 'cat', f'/data/user/0/{PKG}/shared_prefs/airplay_bridge.xml')
        assert 'restore_pending' not in bridge
        return 'installed independent receiver launched, CastHub disabled, return prompt restored listeners'
    def run(self):
        assert self.adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
        self.restart()
        server = ThreadingHTTPServer(('0.0.0.0', 8904), Drip)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            self.case('DLNA buffering watchdog', lambda: self.stall('DLNA'))
            self.case('AirPlay buffering watchdog', lambda: self.stall('AirPlay'))
            self.case('paused playback is not a stall', self.pause_not_stall)
            self.case('DLNA actions/play modes/next previous', self.transport)
            self.case('system media session and notification', self.media_session)
            self.case('repeated takeover does not trigger stale SystemUI stop', self.repeated_takeover)
            self.case('playback quality diagnostics UI', self.quality)
            self.case('cross-protocol language preference persistence', self.track_memory)
            self.case('external SRT on DLNA', lambda: self.external('DLNA', 'srt'))
            self.case('external VTT on AirPlay', lambda: self.external('AirPlay', 'vtt'))
            self.case('local SRT document picker', self.local_subtitle)
            self.case('pause while retry pending', self.pause_pending)
            self.case('DLNA history resume and clear', lambda: self.history_resume('DLNA'))
            self.case('AirPlay history resume and clear', lambda: self.history_resume('AirPlay'))
            self.case('explicit sender start overrides history', self.explicit_start)
            self.case('independent AirPlay return linkage', self.bridge)
        finally:
            Drip.healthy = True; server.shutdown()
            self.prefs(history_enabled=True, audio_language='', text_language='', subtitles_enabled=True,
                auto_retry=True, stall_recovery=True, playback_mode='SINGLE', takeover_policy='ALLOW')
            self.capture_log()
            (self.out/'acceptance-145-results.json').write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding='utf-8')
            (self.out/'acceptance-145-logcat.txt').write_text('\n'.join(self.logs), encoding='utf-8')
        return 0 if all(r['pass'] for r in self.results) else 1


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser()
    p.add_argument('--adb', default=r'D:\Android\Sdk\platform-tools\adb.exe')
    p.add_argument('--serial', default='emulator-5554')
    p.add_argument('--media', default='http://10.0.2.2:8899/test.mp4')
    p.add_argument('--output', default='dist/validation-1.4.5')
    raise SystemExit(NewAcceptance(p.parse_args()).run())
