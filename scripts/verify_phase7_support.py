"""Reuse the baseline HTTP client; no additional Python dependencies."""
import importlib.util
from pathlib import Path
import socket
import time
import urllib.request

spec = importlib.util.spec_from_file_location('phase1', Path(__file__).with_name('verify-phase1.py'))
phase1 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(phase1)
Client, until = phase1.Client, phase1.until

def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

def wait_url(url, process):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        assert process.poll() is None, 'Process exited; inspect phase 7 ignored logs'
        try:
            with urllib.request.urlopen(url, timeout=2): return
        except OSError: time.sleep(.2)
    raise RuntimeError('Server readiness timed out')
