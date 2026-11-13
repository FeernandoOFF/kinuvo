import os
import subprocess
import tempfile
import unittest
from pathlib import Path


class NotarizationTest(unittest.TestCase):
    def test_only_accepted_submissions_are_stapled_and_assessed(self):
        for status in ('Accepted', 'Invalid', 'In Progress'):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                tool = root / 'xcrun'
                tool.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
with open(os.environ['TRACE'], 'a') as trace:
    trace.write(' '.join(sys.argv[1:]) + '\\n')
if sys.argv[1:3] == ['notarytool', 'submit']:
    print(json.dumps({'id': 'submission', 'status': os.environ['NOTARY_STATUS']}))
elif sys.argv[1:3] == ['notarytool', 'log']:
    Path(sys.argv[-1]).write_text('{}')
''')
                tool.chmod(0o755)
                (root / 'spctl').write_text('#!/bin/sh\nprintf "assess\\n" >> "$TRACE"\n')
                (root / 'spctl').chmod(0o755)
                trace = root / 'trace'
                result = subprocess.run(['bash', 'scripts/notarize-macos.sh', str(root / 'test.dmg')],
                                        env={**os.environ, 'PATH': f'{root}:{os.environ["PATH"]}',
                                             'TRACE': str(trace), 'NOTARY_STATUS': status,
                                             'KINUVO_MACOS_NOTARY_PROFILE': 'test-profile'},
                                        capture_output=True, text=True)
                calls = trace.read_text()
                self.assertEqual(result.returncode == 0, status == 'Accepted', result.stderr)
                self.assertIn('notarytool log submission', calls)
                self.assertEqual('stapler staple' in calls, status == 'Accepted')
                self.assertEqual('stapler validate' in calls, status == 'Accepted')
                self.assertEqual('assess\n' in calls, status == 'Accepted')


unittest.main()
