import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
profile = sys.argv[1] if len(sys.argv) > 1 else ''
if profile == 'test':
    home, port = str(Path.home() / '.kinuvo-test'), 8791
elif profile == 'personal':
    home, port = str(Path.home() / '.kinuvo'), 8787
else:
    sys.exit('Expected test or personal desktop profile')
print(f'Kinuvo home: {home}\nPort: {port}', flush=True)
environment = dict(os.environ, KINUVO_RUN_HOME=home, KINUVO_RUN_PORT=str(port))
raise SystemExit(subprocess.call(['mise', 'run', 'desktop', '--', *sys.argv[2:]], cwd=root, env=environment))
