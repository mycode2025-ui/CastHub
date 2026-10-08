#!/usr/bin/env python3
"""Exercise CastHub using unmodified cnelson/python-airplay, not our own sender.

Download the upstream source separately and pass --source. Use ADB port forwarding
for a virtual device; this test deliberately does not claim mDNS interoperability.
"""
import argparse
import json
import sys
import time
from pathlib import Path


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--source', required=True)
    args.add_argument('--host', default='127.0.0.1')
    args.add_argument('--port', type=int, default=37000)
    args.add_argument('--media', default='http://10.0.2.2:8899/test.mp4')
    args.add_argument('--output', default='dist/validation/independent-airplay.json')
    options = args.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    sys.path.insert(0, str(Path(options.source).resolve()))
    from airplay import AirPlay
    client = AirPlay(options.host, options.port)
    results = []

    def check(name, condition, detail=''):
        results.append(dict(name=name, passed=bool(condition), detail=detail))
        print(f"{'PASS' if condition else 'FAIL'}: {name} {detail}", flush=True)

    def ready():
        for _ in range(30):
            info = client.playback_info()
            if info.get('readyToPlay') and info.get('duration', 0) > 0:
                return info
            time.sleep(.5)
        return client.playback_info()

    try:
        check('server-info', 'deviceid' in client.server_info())
        check('text/parameters play request accepted', client.play(options.media))
        before = ready()
        time.sleep(2)
        after = client.playback_info()
        check('actual playback advances', after.get('position', 0) > before.get('position', 0), str(after))
        client.rate(0)
        time.sleep(1)
        before = client.playback_info()['position']
        time.sleep(1)
        check('pause keeps position', abs(client.playback_info()['position'] - before) < .6)
        client.rate(1)
        time.sleep(1.5)
        check('resume advances', client.playback_info()['position'] > before + .3)
        check('fractional start accepted', client.play(options.media, position=.5))
        info = ready()
        time.sleep(.8)
        info = client.playback_info()
        check('Start-Position 0.5 starts at half duration', abs(info['position'] - info['duration'] / 2) < 3, str(info))
        check('stop accepted', client.stop())
        time.sleep(.7)
        check('stop clears actual playback', client.playback_info()['position'] == 0)
    finally:
        client.control_socket.close()
    Path(options.output).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    return 0 if all(result['passed'] for result in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
