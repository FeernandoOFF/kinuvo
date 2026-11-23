import datetime as dt
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import io
import json
from supervisor import next_reset, reset_state, model_environment


class ResetTest(unittest.TestCase):
    def test_model_key_is_withheld_without_a_verified_daily_cap(self):
        with patch.dict("os.environ", {"KINUVO_REVIEW_API_KEY": "unverified"}):
            with patch.object(Path, "exists", return_value=False):
                self.assertNotIn("KINUVO_REVIEW_API_KEY", model_environment())
            for data in ({"limit": None}, {"limit": 2, "limit_reset": "daily"}, {"limit": 1, "limit_reset": "monthly"}):
                with patch.object(Path, "exists", return_value=True), patch.object(Path, "read_text", return_value="test-key"), patch("urllib.request.urlopen", return_value=io.BytesIO(json.dumps({"data": data}).encode())):
                    with self.assertRaises(RuntimeError):
                        model_environment()

    def test_verified_key_is_passed_only_to_child_environment(self):
        with patch.object(Path, "exists", return_value=True), patch.object(Path, "read_text", return_value="test-key"), patch("urllib.request.urlopen", return_value=io.BytesIO(b'{"data":{"limit":1,"limit_reset":"daily"}}')):
            self.assertEqual(model_environment()["KINUVO_REVIEW_API_KEY"], "test-key")

    def test_next_reset_is_0300_utc(self):
        now = dt.datetime(2026, 11, 23, 2, 59, tzinfo=dt.timezone.utc)
        self.assertEqual(next_reset(now), now.replace(hour=3, minute=0))
        self.assertEqual(next_reset(now.replace(hour=3, minute=0)), now.replace(day=24, hour=3, minute=0))

    def test_reset_keeps_identity_and_parent_credentials_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "state"
            home.mkdir()
            (home / ".review-server").touch()
            (home / "server-id").write_text("same-server")
            (home / "personal-edit").touch()
            (root / "review-devices.json").write_text("paired")
            (root / "openrouter-key").write_text("test-fixture")
            reset_state(home)
            self.assertEqual((home / "server-id").read_text(), "same-server")
            self.assertFalse((home / "personal-edit").exists())
            self.assertEqual((root / "review-devices.json").read_text(), "paired")
            self.assertEqual((root / "openrouter-key").read_text(), "test-fixture")

    def test_reset_refuses_unmarked_data_and_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / "state"
            home.mkdir()
            with self.assertRaises(RuntimeError):
                reset_state(home)
            link = Path(directory) / "link"
            link.symlink_to(home)
            with self.assertRaises(RuntimeError):
                reset_state(link)


if __name__ == "__main__":
    unittest.main()
