import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class DevTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = Path(__file__).resolve().parent.parent
        (self.root / 'scripts').mkdir()
        for name in ['dev', 'scripts/project-env.sh']:
            shutil.copy2(source / name, self.root / name)
        self.kotlin = self.root / 'kotlin'
        self.kotlin.write_text('#!/bin/sh\nprintf "%s\\n" "$PWD" "$KINUVO_HOME" "$KINUVO_EMBEDDED_PORT" "$@"\n')
        self.kotlin.chmod(0o755)
        self.home = self.root / 'data'
        self.env = {k: v for k, v in os.environ.items()
                    if k not in ['KINUVO_HOME', 'KINUVO_EMBEDDED_PORT']}

    def run_dev(self, *args, env=None):
        return subprocess.run([str(self.root / 'dev'), *args], cwd='/',
                              env=env or self.env, text=True, capture_output=True)

    def init(self):
        result = self.run_dev('init', str(self.home), '8790')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_init_does_not_create_data_or_overwrite_pins(self):
        self.init()
        self.assertFalse(self.home.exists())
        result = self.run_dev('init', str(self.root / 'other'), '8791')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / '.kinuvo-home').read_text(), str(self.home) + '\n')
        self.assertEqual((self.root / '.kinuvo-port').read_text(), '8790\n')

    def test_invalid_setup_leaves_no_pins(self):
        for home, port in [('relative', '8790'), (str(self.home), '0'),
                           (str(self.home), '65536'), (str(self.home), 'text')]:
            result = self.run_dev('init', home, port)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((self.root / '.kinuvo-home').exists())
            self.assertFalse((self.root / '.kinuvo-port').exists())

    def test_help_and_missing_setup(self):
        self.assertEqual(self.run_dev('help').returncode, 0)
        self.assertNotEqual(self.run_dev('desktop').returncode, 0)

    def test_launcher_binds_code_home_and_port(self):
        self.init()
        result = self.run_dev('kotlin', 'run', '-m', 'desktop')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(),
                         [str(self.root), str(self.home), '8790', 'run', '-m', 'desktop'])
        result = self.run_dev('server')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('--port\n8790\n', result.stdout)

    def test_conflicting_environment_is_rejected(self):
        self.init()
        for key, value in [('KINUVO_HOME', str(self.root / 'wrong')),
                           ('KINUVO_EMBEDDED_PORT', '8791')]:
            result = self.run_dev('kotlin', '--help', env={**self.env, key: value})
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('conflicts with this checkout', result.stderr)

    def test_live_server_blocks_another_launch(self):
        self.init()
        self.home.mkdir()
        (self.home / 'server.json').write_text(json.dumps({'pid': os.getpid()}))
        for command in ['server', 'desktop']:
            result = self.run_dev(command)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('already owns', result.stderr)


if __name__ == '__main__':
    unittest.main()
