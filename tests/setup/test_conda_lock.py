"""Check packaged lockfiles and actionable missing-lockfile errors without Conda."""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class CondaLockPackaging(unittest.TestCase):
    def test_both_lockfiles_are_packaged(self):
        for suffix in ('', '-lean'):
            path = ROOT / 'conda-reqs/conda-lock-reqs' / (
                f'conda-requirements-riscv-tools-linux-64{suffix}.conda-lock.yml')
            self.assertTrue(path.is_file(), str(path))
            self.assertIn('version: 1\n', path.read_text())
            self.assertIn('- name: riscv-tools\n', path.read_text())

    def test_missing_lockfiles_fail_before_environment_creation(self):
        for options, suffix in (([], ''), (['--use-lean-conda'], '-lean')):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as tmp:
                root = pathlib.Path(tmp)
                (root / 'scripts').mkdir()
                shutil.copy(ROOT / 'scripts/build-setup.sh', root / 'build-setup.sh')
                shutil.copy(ROOT / 'scripts/utils.sh', root / 'scripts/utils.sh')
                subprocess.run(['git', 'init', '-q', tmp], check=True)
                result = subprocess.run(['bash', 'build-setup.sh', *options],
                                        cwd=root, text=True, capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Missing packaged Conda lockfile:', result.stdout + result.stderr)
                self.assertIn(f'linux-64{suffix}.conda-lock.yml', result.stdout + result.stderr)
                self.assertFalse((root / '.conda-lock-env').exists())
                self.assertFalse((root / '.conda-env').exists())


if __name__ == '__main__':
    unittest.main()
