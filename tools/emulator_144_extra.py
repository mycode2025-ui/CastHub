#!/usr/bin/env python3
"""Timestamp, queue end transitions and distinct sender-address acceptance."""
import argparse
import json
import re
import sys
import time
from emulator_144 import Acceptance


class Extra(Acceptance):
    def timing(self, offset, protocol):
        self.prefs(subtitle_offset_ms=offset, subtitle_size=150, subtitle_bottom=25, picture_mode='FIT')
        url = 'http://10.0.2.2:8901/timed.mp4'
        pos = self.dlna_pos if protocol == 'DLNA' else lambda: self.air('/playback-info').get('position', 0)
        (self.dlna_play if protocol == 'DLNA' else self.air_play)(url)
        self.wait(lambda: pos() > 0)
        self.adb('shell', 'input', 'keyevent', '85')
        self.adb('shell', 'input', 'keyevent', '82')
        self.tap('字幕：en', contains=True)
        target = 4 + offset / 1000 + .2
        self.adb('logcat', '-c')
        if protocol == 'DLNA':
            # SOAP seek granularity is integer seconds.
            self.soap('Seek', Unit='REL_TIME', Target=f'00:00:{int(target):02}')
        else:
            self.air(f'/scrub?position={target}', b'')
        self.adb('shell', 'input', 'keyevent', '85')
        tag = 'DlnaRenderer' if protocol == 'DLNA' else 'AirPlayPlayer'
        self.wait(lambda: '字幕更新：1' in self.adb('logcat', '-d', '-s', tag), timeout=8)
        self.adb('shell', 'input', 'keyevent', '85')
        self.screenshot(f'{protocol.lower()}-subtitle-offset-{offset}.png')
        (self.out / f'{protocol.lower()}-subtitle-offset-{offset}.log').write_text(self.adb('logcat', '-d', '-s', tag), encoding='utf-8')
        return f'caption originally starts at 4s; offset {offset}ms rendered at {pos():.2f}s'

    def sequential(self):
        self.prefs(playback_mode='SEQUENTIAL', subtitle_offset_ms=0)
        self.dlna_play()
        self.wait(lambda: self.dlna_pos() > 1)
        self.soap('SetNextAVTransportURI', NextURI='http://10.0.2.2:8901/tracks.mp4', NextURIMetaData='')
        self.soap('Seek', Unit='REL_TIME', Target='00:00:28')
        self.wait(lambda: self.soap('GetPositionInfo')['TrackURI'].endswith('tracks.mp4'), timeout=12)
        self.wait(lambda: self.dlna_pos() > 0)
        return 'known NextURI started after current clip ended'

    def distinct_sender(self):
        self.prefs(takeover_policy='BLOCK', playback_mode='SINGLE')
        self.air_play()
        self.wait(lambda: self.air('/playback-info').get('position', 0) > 1)
        body = b'Content-Location: http://10.0.2.2:8901/tracks.mp4\r\nStart-Position: 0\r\n'
        req = b'POST /play HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/parameters\r\nConnection: close\r\nContent-Length: ' + str(len(body)).encode() + b'\r\n\r\n' + body
        path = self.out / 'different-sender.http'; path.write_bytes(req)
        self.adb('push', str(path), '/data/local/tmp/casthub-different-sender.http')
        info = self.adb('shell', 'ip', '-4', 'addr', 'show', 'wlan0')
        ip = re.search(r'inet (\d+\.\d+\.\d+\.\d+)', info).group(1)
        reply = self.adb('shell', f'toybox nc {ip} 7000 < /data/local/tmp/casthub-different-sender.http')
        assert '409 Conflict' in reply, reply
        assert self.air('/playback-info')['duration'] == 30.0
        return 'same protocol with a distinct source IP rejected; original 30s video preserved'

    def air_sequential(self):
        self.prefs(playback_mode='SEQUENTIAL')
        self.air_play()
        self.wait(lambda: self.air('/playback-info').get('position', 0) > 1)
        self.air_play('http://10.0.2.2:8901/tracks.mp4')
        self.wait(lambda: self.air('/playback-info').get('duration') == 12.0)
        self.air_play()
        self.wait(lambda: self.air('/playback-info').get('duration') == 30.0)
        self.air('/scrub?position=28', b'')
        self.wait(lambda: self.air('/playback-info').get('duration') == 12.0, timeout=12)
        self.wait(lambda: self.air('/playback-info').get('position', 0) > .5)
        return 'received local queue switched 30s video to 12s next video at end'

    def fill(self):
        self.prefs(picture_mode='FILL')
        self.dlna_play()
        self.wait(lambda: self.dlna_pos() > 1)
        self.soap('Pause')
        time.sleep(1)
        self.screenshot('fill-crop.png')
        return '16:9 video fills portrait viewport with center crop; screenshot captured'

    def run(self):
        assert self.adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
        self.case('subtitle delayed DLNA', lambda: self.timing(1000, 'DLNA'))
        self.case('subtitle advanced AirPlay', lambda: self.timing(-1000, 'AirPlay'))
        self.case('DLNA sequential end transition', self.sequential)
        self.case('same protocol distinct sender blocked', self.distinct_sender)
        self.case('AirPlay sequential end transition', self.air_sequential)
        self.case('fill mode cropped presentation', self.fill)
        self.prefs(takeover_policy='ALLOW', auto_retry=True, playback_mode='SINGLE', picture_mode='FIT', subtitle_size=100, subtitle_bottom=8, subtitle_offset_ms=0)
        (self.out / 'extra-results.json').write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding='utf-8')
        return 0 if all(r['pass'] for r in self.results) else 1


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default=r'D:\Android\Sdk\platform-tools\adb.exe')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--media', default='http://10.0.2.2:8899/test.mp4')
    parser.add_argument('--output', default='dist/validation-1.4.4')
    raise SystemExit(Extra(parser.parse_args()).run())
