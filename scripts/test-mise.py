import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class MiseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "checkout with spaces"
        self.root.mkdir()
        source = Path(__file__).resolve().parent.parent
        (self.root / 'scripts').mkdir()
        for name in ['mise.toml', 'scripts/project-env.sh', 'scripts/project-run.sh', 'scripts/init-checkout.py']:
            shutil.copy2(source / name, self.root / name)
        self.kotlin = self.root / 'kotlin'
        self.kotlin.write_text('#!/bin/sh\nprintf "%s\\n" "$PWD" "$KINUVO_HOME" "$KINUVO_EMBEDDED_PORT" "$@"\n')
        self.kotlin.chmod(0o755)
        self.home = self.root / 'data'
        self.env = {k: v for k, v in os.environ.items()
                    if k not in ['KINUVO_HOME', 'KINUVO_EMBEDDED_PORT'] and not k.startswith('MISE_')}
        self.env.update(MISE_TRUSTED_CONFIG_PATHS=str(self.root),
                        MISE_GLOBAL_CONFIG_FILE='/dev/null',
                        MISE_SYSTEM_CONFIG_FILE='/dev/null',
                        MISE_CACHE_DIR=str(Path(self.temp.name) / 'cache'),
                        MISE_STATE_DIR=str(Path(self.temp.name) / 'state'))

    def run_mise(self, task, *args, env=None, cwd=None):
        return subprocess.run(['mise', '--quiet', '--cd', str(cwd or self.root),
                               'run', task, '--', *args], cwd='/',
                              env=env or self.env, text=True, capture_output=True)

    def init(self):
        result = self.run_mise('init', str(self.home), '8790')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_init_does_not_create_data_or_overwrite_pins(self):
        self.init()
        self.assertFalse(self.home.exists())
        result = self.run_mise('init', str(self.root / 'other'), '8791')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / '.kinuvo-home').read_text(), str(self.home) + '\n')
        self.assertEqual((self.root / '.kinuvo-port').read_text(), '8790\n')

    def test_invalid_setup_leaves_no_pins(self):
        for home, port in [('relative', '8790'), (str(self.home), '0'),
                           (str(self.home), '65536'), (str(self.home), 'text')]:
            result = self.run_mise('init', home, port)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((self.root / '.kinuvo-home').exists())
            self.assertFalse((self.root / '.kinuvo-port').exists())

    def test_missing_setup(self):
        self.assertNotEqual(self.run_mise('desktop').returncode, 0)

    def test_tasks_bind_code_home_and_port(self):
        self.init()
        result = self.run_mise('kotlin', 'run', '-m', 'desktop')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(),
                         [str(self.root.resolve()), str(self.home), '8790', 'run', '-m', 'desktop'])
        result = self.run_mise('server')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('--port\n8790\n', result.stdout)

    def test_conflicting_environment_is_rejected(self):
        self.init()
        for key, value in [('KINUVO_HOME', str(self.root / 'wrong')),
                           ('KINUVO_EMBEDDED_PORT', '8791')]:
            result = self.run_mise('kotlin', '--help', env={**self.env, key: value})
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('conflicts with this checkout', result.stderr)

    def test_live_server_blocks_another_launch(self):
        self.init()
        self.home.mkdir()
        (self.home / 'server.json').write_text(json.dumps({'pid': os.getpid()}))
        for command in ['server', 'desktop']:
            result = self.run_mise(command)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('already owns', result.stderr)

    def test_platform_arguments_and_cli_separator(self):
        self.init()
        for task in ['ios', 'android', 'cli']:
            result = self.run_mise(task, '--device-id', 'two words', '--literal', '$(false)')
            self.assertEqual(result.returncode, 0, result.stderr)
            expected = ['run', '-m', task]
            if task == 'cli':
                expected.append('--')
            self.assertEqual(result.stdout.splitlines()[3:],
                             expected + ['--device-id', 'two words', '--literal', '$(false)'])

    def test_subdirectory_and_arbitrary_command(self):
        self.init()
        result = self.run_mise('exec', 'python3', '-c',
                              'import os; print(os.getcwd()); print(os.environ["KINUVO_HOME"])',
                              cwd=self.root / 'scripts')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), [str(self.root.resolve()), str(self.home)])

    def test_invalid_server_record_blocks_launch(self):
        self.init()
        self.home.mkdir()
        (self.home / 'server.json').write_text('invalid json')
        result = self.run_mise('server')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Cannot validate', result.stderr)


if __name__ == '__main__':
    unittest.main()
