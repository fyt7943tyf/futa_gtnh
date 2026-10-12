import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch
import zipfile

import release as r


class PureRules(unittest.TestCase):
    def test_default_patch(self):
        self.assertEqual(r.bump_version('1.28.2', ['1.28.2'], 'patch'), '1.28.3')

    def test_minor_and_major_reset_lower_components(self):
        self.assertEqual(r.bump_version('1.28.2', [], 'minor'), '1.29.0')
        self.assertEqual(r.bump_version('1.28.2', [], 'major'), '2.0.0')

    def test_skip_reserved_tags_with_numeric_order(self):
        self.assertEqual(r.bump_version('1.28.2', ['1.28.9', '1.28.10'], 'patch'), '1.28.11')

    def test_invalid_versions_are_rejected(self):
        for value in ['v1.28.2', '1.28', '01.28.2', '1.28.2-dev', '1.28.2\n', '-1.0.0']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                r.version(value)

    def test_only_numeric_release_tags_are_reserved(self):
        self.assertEqual(r.reserved_versions(['v1.28.2', 'dev', 'v1.29.0-beta'],
            [{'tag_name': 'v1.28.3', 'draft': True}]), ['1.28.2', '1.28.3'])

    def test_published_release_cannot_be_reused(self):
        for item in [{'draft': False}, {'draft': False, 'prerelease': True}, {}]:
            with self.subTest(item=item), self.assertRaises(ValueError):
                r.require_unpublished(item)
        r.require_unpublished(None)
        r.require_unpublished({'draft': True})

    def test_assets_require_matching_size_and_digest(self):
        expected = {'futa.jar': {'size': 10, 'digest': 'sha256:' + 'a' * 64}}
        valid = {'name': 'futa.jar', 'size': 10, 'state': 'uploaded', 'digest': 'sha256:' + 'a' * 64}
        r.validate_assets(expected, [valid])
        for items in [[], [dict(valid, size=9)], [dict(valid, state='new')],
                      [dict(valid, digest='sha256:' + 'b' * 64)], [valid, valid],
                      [valid, dict(valid, name='other.jar')]]:
            with self.subTest(items=items), self.assertRaises(ValueError):
                r.validate_assets(expected, items)

    def test_asset_without_github_digest_still_requires_size(self):
        r.validate_assets({'futa.jar': {'size': 10, 'digest': 'sha256:' + 'a' * 64}},
                          [{'name': 'futa.jar', 'size': 10, 'state': 'uploaded'}])


class ReleaseTransactions(unittest.TestCase):
    def setUp(self):
        base = (Path(__file__).resolve().parents[2] / 'build' / 'release-tests').resolve()
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=base)
        self.root = Path(self.temp.name).resolve()
        self.root.relative_to(base)
        self.before_cwd = Path.cwd()
        os.chdir(self.root)
        r.git('init', '--bare', '--initial-branch=main', 'remote.git')
        r.git('clone', 'remote.git', 'work')
        os.chdir(self.root / 'work')
        r.git('config', 'user.name', 'Release fixture')
        r.git('config', 'user.email', 'fixture@example.invalid')
        Path('version.txt').write_text('1.28.2\n', encoding='utf-8')
        r.git('add', 'version.txt')
        r.git('commit', '-m', 'fixture')
        r.git('tag', 'v1.28.2')
        r.git('push', 'origin', 'main', 'v1.28.2')
        self.sha = r.git('rev-parse', 'HEAD')
        self.state = self.root / 'plan.json'
        self.env = patch.dict(os.environ, {'GITHUB_REF': 'refs/heads/main', 'GITHUB_SHA': self.sha,
            'GITHUB_OUTPUT': str(self.root / 'outputs'), 'GITHUB_STEP_SUMMARY': str(self.root / 'summary')})
        self.env.start()
        self.github = patch.object(r, 'github', return_value=None)
        self.api = self.github.start()
        self.listing = patch.object(r, 'releases', return_value=[{'tag_name': 'v1.28.2', 'draft': False, 'prerelease': False}])
        self.listing.start()
        self.silence = contextlib.redirect_stdout(io.StringIO())
        self.silence.__enter__()

    def tearDown(self):
        self.silence.__exit__(None, None, None)
        self.listing.stop()
        self.github.stop()
        self.env.stop()
        os.chdir(self.before_cwd)
        self.temp.cleanup()

    def plan(self):
        return json.loads(self.state.read_text(encoding='utf-8'))

    def prepare_verified(self, metadata_version='1.28.3', modid='futa_gtnh', entry=True):
        r.prepare(self.state, 'patch')
        plan = self.plan()
        libs = Path('build/libs')
        libs.mkdir(parents=True)
        with zipfile.ZipFile(libs / 'futa_gtnh-1.28.3.jar', 'w') as archive:
            archive.writestr('mcmod.info', json.dumps([{'modid': modid, 'version': metadata_version}]))
            if entry:
                archive.writestr('com/futa_gtnh/FutaGtnhMod.class', b'fixture')
        r.verify(self.state, plan)
        return self.plan()

    def test_prepare_leaves_remote_version_unchanged(self):
        r.prepare(self.state, 'patch')
        self.assertEqual(Path('version.txt').read_text().strip(), '1.28.3')
        self.assertEqual(r.git('--git-dir=../remote.git', 'show', 'main:version.txt').strip(), '1.28.2')
        self.assertIsNone(r.remote_tag('v1.28.3'))

    def test_wrong_branch_cannot_release(self):
        os.environ['GITHUB_REF'] = 'refs/heads/feature'
        with self.assertRaises(ValueError):
            r.prepare(self.state, 'patch')

    def test_stale_dispatch_cannot_release(self):
        os.environ['GITHUB_SHA'] = '0' * 40
        with self.assertRaises(ValueError):
            r.prepare(self.state, 'patch')
        self.assertEqual(Path('version.txt').read_text().strip(), '1.28.2')

    def test_jar_version_mismatch_blocks_version_commit(self):
        with self.assertRaises(ValueError):
            self.prepare_verified(metadata_version='1.28.2')
        self.assertEqual(r.git('rev-parse', 'origin/main'), self.sha)

    def test_wrong_modid_is_rejected(self):
        with self.assertRaises(ValueError):
            self.prepare_verified(modid='another_mod')

    def test_missing_entry_is_rejected(self):
        with self.assertRaises(ValueError):
            self.prepare_verified(entry=False)

    def test_record_atomically_publishes_matching_branch_and_tag(self):
        plan = self.prepare_verified()
        r.record(self.state, plan)
        branch = r.git('--git-dir=../remote.git', 'rev-parse', 'main')
        tagged = r.git('--git-dir=../remote.git', 'rev-parse', 'v1.28.3^{commit}')
        self.assertEqual(branch, tagged)
        self.assertEqual(r.git('--git-dir=../remote.git', 'show', 'main:version.txt').strip(), '1.28.3')

    def test_failed_atomic_push_does_not_write_version_or_tag(self):
        plan = self.prepare_verified()
        hook = self.root / 'remote.git/hooks/pre-receive'
        hook.write_text('#!/bin/sh\nwhile read old new ref; do\n'
                        'case "$ref" in refs/tags/v1.28.3) exit 1;; esac\ndone\n', encoding='utf-8', newline='\n')
        hook.chmod(0o755)
        with self.assertRaises(subprocess.CalledProcessError):
            r.record(self.state, plan)
        self.assertEqual(r.git('--git-dir=../remote.git', 'rev-parse', 'main'), self.sha)
        self.assertIsNone(r.remote_tag('v1.28.3'))

    def test_main_advanced_during_build_cannot_be_overwritten(self):
        plan = self.prepare_verified()
        r.git('clone', '../remote.git', '../other')
        r.git('-C', '../other', 'config', 'user.name', 'Concurrent developer')
        r.git('-C', '../other', 'config', 'user.email', 'other@example.invalid')
        (self.root / 'other/change.txt').write_text('new work', encoding='utf-8')
        r.git('-C', '../other', 'add', 'change.txt')
        r.git('-C', '../other', 'commit', '-m', 'concurrent change')
        r.git('-C', '../other', 'push', 'origin', 'main')
        with self.assertRaises(ValueError):
            r.record(self.state, plan)
        self.assertIsNone(r.remote_tag('v1.28.3'))

    def test_retry_published_version_is_rejected(self):
        with self.assertRaises(ValueError):
            r.prepare(self.state, 'retry')

    def test_retry_rebuilds_pending_tag_and_keeps_version(self):
        plan = self.prepare_verified()
        r.record(self.state, plan)
        commit = r.git('rev-parse', 'HEAD')
        os.environ['GITHUB_SHA'] = commit
        self.listing.stop()
        self.listing = patch.object(r, 'releases', return_value=[{'tag_name': 'v1.28.3', 'draft': True}])
        self.listing.start()
        r.prepare(self.state, 'retry')
        self.assertEqual(self.plan()['version'], '1.28.3')
        self.assertEqual(self.plan()['commit'], commit)
        self.assertEqual(r.git('rev-parse', 'HEAD'), commit)

    def test_record_requires_verified_build(self):
        r.prepare(self.state, 'patch')
        with self.assertRaises(ValueError):
            r.record(self.state, self.plan())

    def test_publish_waits_for_complete_assets(self):
        plan = self.prepare_verified()
        plan.update(release_id=123, tag_object='tag-sha')
        self.api.return_value = {'draft': True, 'tag_name': plan['tag'], 'assets': []}
        with patch.object(r, 'check_remote'), self.assertRaises(ValueError):
            r.publish(self.state, plan)
        self.assertFalse(any(call.args[0] == '/releases/123' and len(call.args) > 1 for call in self.api.call_args_list))

    def test_publish_rejects_a_newer_published_release(self):
        plan = self.prepare_verified()
        plan.update(release_id=123, tag_object='tag-sha')
        uploaded = [{'name': name, 'state': 'uploaded', **metadata} for name, metadata in plan['assets'].items()]
        self.api.return_value = {'draft': True, 'tag_name': plan['tag'], 'assets': uploaded}
        with patch.object(r, 'check_remote'), patch.object(r, 'releases', return_value=[
                {'tag_name': 'v1.28.4', 'draft': False, 'prerelease': False}]), self.assertRaises(ValueError):
            r.publish(self.state, plan)

    def test_publish_complete_draft_and_mark_latest(self):
        plan = self.prepare_verified()
        plan.update(release_id=123, tag_object='tag-sha')
        uploaded = [{'name': name, 'state': 'uploaded', **metadata} for name, metadata in plan['assets'].items()]
        self.api.side_effect = [
            {'draft': True, 'tag_name': plan['tag'], 'assets': uploaded},
            {'draft': False, 'html_url': 'https://example.invalid/release'}]
        with patch.object(r, 'check_remote'):
            r.publish(self.state, plan)
        self.assertEqual(self.api.call_args.args, ('/releases/123', {'draft': False, 'make_latest': 'true'}, 'PATCH'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
