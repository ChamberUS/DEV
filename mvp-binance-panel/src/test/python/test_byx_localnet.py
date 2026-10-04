import importlib.util
import json
from pathlib import Path
import tempfile
import socket
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('localnet', Path(__file__).parents[3] / 'scripts/byx_localnet.py')
localnet = importlib.util.module_from_spec(spec)
spec.loader.exec_module(localnet)


class LocalnetGuardsTest(unittest.TestCase):
    def test_init_refuses_existing_home(self):
        with tempfile.TemporaryDirectory() as d, patch.object(localnet, 'NODE', Path(d)):
            with self.assertRaisesRegex(RuntimeError, 'overwrite'):
                localnet.init()

    def test_sdk_genesis_conversion_does_not_change_app_state(self):
        g = dict(genesis_time='2026-01-01T00:00:00Z', chain_id='test', initial_height=1,
                 app_hash=None, app_state={'bank': {'balances': []}}, consensus={'params': {}})
        out = localnet.rpc_genesis(g)
        self.assertEqual('1', out['initial_height'])
        self.assertEqual('', out['app_hash'])
        self.assertEqual(g['app_state'], out['app_state'])
        self.assertNotIn('app_name', out)

    def test_pid_reuse_refuses_signals(self):
        with tempfile.TemporaryDirectory() as d, patch.object(localnet, 'ROOT', Path(d)):
            (Path(d) / 'node.pid').write_text(json.dumps({'pid': 42, 'identity': 'original'}))
            with patch.object(localnet.subprocess, 'run') as run:
                run.return_value.returncode = 0
                run.return_value.stdout = 'another process'
                with self.assertRaisesRegex(RuntimeError, 'PID identity mismatch'):
                    localnet.process()

    def test_duplicate_start_refused(self):
        with patch.object(localnet, 'checked'), patch.object(localnet, 'process', return_value=42):
            with self.assertRaisesRegex(RuntimeError, 'duplicate'):
                localnet.start()

    def test_live_listener_refuses_start_even_with_reuse(self):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            with patch.object(localnet, 'checked'), patch.object(localnet, 'process', return_value=None), \
                    patch.object(localnet, 'PORTS', {'rpc': listener.getsockname()[1]}):
                with self.assertRaises(OSError):
                    localnet.start()

    def test_wrong_marker_refused(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'manifest.json'
            path.write_text(json.dumps({'purpose': 'historical node'}))
            with patch.object(localnet, 'MANIFEST', path):
                with self.assertRaisesRegex(RuntimeError, 'identity/config changed'):
                    localnet.checked()

    def test_stop_uses_sigterm_only(self):
        with tempfile.TemporaryDirectory() as d, patch.object(localnet, 'ROOT', Path(d)), \
                patch.object(localnet, 'checked'), patch.object(localnet, 'process', side_effect=[42, None]), \
                patch.object(localnet.os, 'kill') as kill:
            localnet.stop()
            kill.assert_called_once_with(42, localnet.signal.SIGTERM)


if __name__ == '__main__':
    unittest.main()
