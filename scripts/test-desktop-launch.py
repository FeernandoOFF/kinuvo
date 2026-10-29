import os
from pathlib import Path
import runpy
import signal
import subprocess
import unittest
from unittest.mock import Mock, patch


class DesktopLaunchTest(unittest.TestCase):
    def launch(self, lookup):
        handlers = {}
        launcher = Mock()
        launcher.returncode = 0
        polls = iter([None, None, 0])

        def poll():
            result = next(polls)
            if result is None:
                handlers[signal.SIGINT](signal.SIGINT, None)
            return result

        launcher.poll.side_effect = poll
        with patch.dict(os.environ, KINUVO_HOME='/tmp/kinuvo-launch-test', KINUVO_EMBEDDED_PORT='8790'), \
             patch('sys.argv', ['launch-desktop.py', '/tmp/Kinuvo.app', '--example']), \
             patch('signal.signal', side_effect=lambda number, handler: handlers.update({number: handler})), \
             patch('subprocess.Popen', return_value=launcher) as start, \
             patch('subprocess.run', side_effect=lookup) as find, \
             patch('os.kill') as kill, patch('time.sleep'):
            with self.assertRaises(SystemExit) as exit:
                runpy.run_path(str(Path(__file__).with_name('launch-desktop.py')), run_name='__main__')
            return exit.exception.code, start, find, kill, launcher

    def test_interrupt_targets_only_token_matched_instance(self):
        code, start, find, kill, _ = self.launch([subprocess.CompletedProcess([], 0, '4321\n', '')])
        self.assertEqual(code, 130)
        kill.assert_called_once_with(4321, signal.SIGTERM)
        command = start.call_args.args[0]
        self.assertTrue(start.call_args.kwargs['start_new_session'])
        self.assertEqual(command[-2], '--example')
        self.assertIn(command[-1], find.call_args.args[0][-1])
        self.assertTrue(find.call_args.args[0][-1].startswith('^' + str(Path('/tmp').resolve()) + '/Kinuvo'))

    def test_interrupt_before_process_appears(self):
        _, _, find, kill, _ = self.launch([
            subprocess.CompletedProcess([], 1, '', ''),
            subprocess.CompletedProcess([], 0, '4321\n', ''),
        ])
        self.assertEqual(find.call_count, 2)
        kill.assert_called_once_with(4321, signal.SIGTERM)

    def test_inspection_failure_is_reported(self):
        code, _, _, kill, launcher = self.launch([subprocess.CompletedProcess([], 2, '', 'denied')])
        self.assertEqual(code, 1)
        kill.assert_not_called()
        launcher.terminate.assert_called_once()


unittest.main()
