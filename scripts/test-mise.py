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
        (self.root / "scripts").mkdir(parents=True)
        source = Path(__file__).resolve().parent.parent
        shutil.copy2(source / "mise.toml", self.root / "mise.toml")
        probe = "#!/usr/bin/env python3\nimport json, os, sys\nprint(json.dumps([os.getcwd(), os.environ.get('KINUVO_HOME'), os.environ.get('KINUVO_EMBEDDED_PORT'), sys.argv[1:]]))\n"
        for name in ["kotlin", "scripts/run-desktop.sh"]:
            path = self.root / name
            path.write_text(probe)
            path.chmod(0o755)
        builder = self.root / "scripts/build-local-voice.sh"
        builder.write_text("#!/bin/sh\nexit 0\n")
        builder.chmod(0o755)
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(("KINUVO_", "MISE_"))}
        self.env.update(MISE_TRUSTED_CONFIG_PATHS=str(self.root),
                        MISE_GLOBAL_CONFIG_FILE="/dev/null", MISE_SYSTEM_CONFIG_FILE="/dev/null",
                        MISE_CACHE_DIR=str(Path(self.temp.name) / "cache"),
                        MISE_STATE_DIR=str(Path(self.temp.name) / "state"))

    def run_mise(self, task, *args, env=None, cwd=None):
        result = subprocess.run(["mise", "--quiet", "--cd", str(cwd or self.root),
                                 "run", task, "--", *args], cwd="/", env=env or self.env,
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def test_named_desktop_homes_are_independent(self):
        for task, home, port in [("desktop:personal", ".kinuvo", "8787"),
                                  ("desktop:test", ".kinuvo-test", "8791")]:
            result = self.run_mise(task, "--compose-hot-reload")
            self.assertEqual(result, [str(self.root.resolve()), str(Path.home() / home), port,
                                      ["--compose-hot-reload"]])

    def test_named_target_overrides_inherited_home(self):
        for target in ["PERSONAL", "TEST"]:
            env = {**self.env, "KINUVO_HOME": "/unrelated", "KINUVO_EMBEDDED_PORT": "9999",
                   "KINUVO_" + target + "_HOME": str(self.root / "selected data"),
                   "KINUVO_" + target + "_PORT": "8799"}
            result = self.run_mise("desktop:" + target.lower(), env=env)
            self.assertEqual(result[1:3], [str(self.root / "selected data"), "8799"])
            self.assertFalse((self.root / "selected data").exists())

    def test_tasks_forward_arguments_without_setup(self):
        args = ["--device-id", "two words", "--literal", "$(false)"]
        for task in ["android", "ios", "cli", "kotlin", "desktop"]:
            result = self.run_mise(task, *args)
            prefix = ["run", "-m", task] if task in ["android", "ios", "cli"] else []
            if task == "cli":
                prefix.append("--")
            self.assertEqual(result, [str(self.root.resolve()), None, None, prefix + args])

    def test_legacy_pins_are_ignored_and_environment_is_preserved(self):
        (self.root / ".kinuvo-home").write_text("/obsolete")
        (self.root / ".kinuvo-port").write_text("9999")
        env = {**self.env, "KINUVO_HOME": str(self.root / "chosen"), "KINUVO_EMBEDDED_PORT": "8792"}
        result = self.run_mise("server", "--port", "8792", env=env)
        self.assertEqual(result[1:3], [str(self.root / "chosen"), "8792"])
        self.assertEqual(result[3], ["run", "-m", "cli", "--", "serve", "--host", "127.0.0.1", "--port", "8792"])

    def test_exec_forwards_from_subdirectory(self):
        result = self.run_mise("exec", "./kotlin", "two words", "$(false)", cwd=self.root / "scripts")
        self.assertEqual(result, [str(self.root.resolve()), None, None, ["two words", "$(false)"]])


if __name__ == "__main__":
    unittest.main()
