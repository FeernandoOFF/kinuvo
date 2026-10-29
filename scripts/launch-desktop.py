import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import uuid

bundle = Path(sys.argv[1]).resolve()
executable = bundle / 'Contents/MacOS/Kinuvo'
token = '--kinuvo-launch-id=' + uuid.uuid4().hex
home = Path(os.environ['KINUVO_HOME'])
interrupted = None


def interrupt(number, _frame):
    global interrupted
    interrupted = number


for number in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
    signal.signal(number, interrupt)

launcher = subprocess.Popen([
    '/usr/bin/open', '-n', '-W',
    '--env', 'KINUVO_HOME=' + str(home),
    '--env', 'KINUVO_EMBEDDED_PORT=' + os.environ['KINUVO_EMBEDDED_PORT'],
    '--stdout', str(home / 'logs/desktop-output.log'),
    '--stderr', str(home / 'logs/desktop-error.log'),
    str(bundle), '--args', *sys.argv[2:], token,
], start_new_session=True)
pattern = '^' + re.escape(str(executable)).replace(r'\-', '-') + ' .*' + token
sent = False
while launcher.poll() is None:
    if interrupted is not None and not sent:
        result = subprocess.run(['/usr/bin/pgrep', '-f', pattern], capture_output=True, text=True)
        if result.returncode not in (0, 1):
            print('Cannot find the launched Kinuvo process: ' + result.stderr.strip(), file=sys.stderr)
            launcher.terminate()
            launcher.wait()
            sys.exit(1)
        for value in result.stdout.splitlines():
            try:
                os.kill(int(value), signal.SIGTERM)
                sent = True
            except ProcessLookupError:
                pass
    time.sleep(0.1)
sys.exit(128 + interrupted if interrupted is not None else launcher.returncode)
