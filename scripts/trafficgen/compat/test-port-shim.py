#!/usr/bin/env python3
"""Exercise port-only redirection with real socket traffic in an isolated child."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile


def exchange(address, family=socket.AF_INET):
    with socket.socket(family) as server:
        server.bind(address)
        actual = server.getsockname()
        server.listen(1)
        target = address if family == socket.AF_UNIX or address[1] else actual
        with socket.socket(family) as client:
            client.connect(target)
            with server.accept()[0] as peer:
                payload = b'\x00\xffunchanged wire bytes\x01'
                client.sendall(payload)
                assert peer.recv(1024) == payload
                peer.sendall(payload[::-1])
                assert client.recv(1024) == payload[::-1]
        return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('shim', type=Path)
    parser.add_argument('--child', action='store_true')
    args = parser.parse_args()
    if args.child:
        redirect = int(os.environ['TG_COMPAT_SOCKET_PORT'])
        assert exchange(('127.0.0.1', 50051)) == ('127.0.0.1', redirect)
        untouched = exchange(('127.0.0.1', 0))
        assert untouched[1] not in (0, 50051, redirect)
        with tempfile.TemporaryDirectory(prefix='tg-port-shim-') as directory:
            path = str(Path(directory) / 'socket')
            assert exchange(path, socket.AF_UNIX) == path
        print(json.dumps({'result': 'PASS', 'redirected_bind_connect': True,
                          'unrelated_inet_and_unix_unchanged': True,
                          'payload_bytes_unchanged': True, 'port': redirect}))
        return
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    env = os.environ.copy()
    if env.get('LD_PRELOAD'):
        parser.error('test requires no existing LD_PRELOAD')
    env.update(LD_PRELOAD=str(args.shim.resolve(strict=True)), TG_COMPAT_SOCKET_PORT=str(port))
    subprocess.run([sys.executable, str(Path(__file__).resolve()), str(args.shim), '--child'],
                   env=env, check=True, timeout=10)


if __name__ == '__main__':
    main()
