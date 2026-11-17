"""Admission checks matter before handling signed release artifacts or publishing."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('staged', Path(__file__).with_name('verify-staged-release.py'))
staged = importlib.util.module_from_spec(spec)
spec.loader.exec_module(staged)


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.manifest = dict(schema=2, version='1.0.1-experimental', versionCode=101,
                             baselineVersion='1.0', baselineVersionCode=100,
                             baselineSource='a' * 40, sourceCommit='b' * 40,
                             sha256={name: 'c' * 64 for name in
                                     ('offgrid-1.0.1-experimental.apk', 'baseline.apk',
                                      'baseline-test.apk', 'upgrade-test.apk')})

    def test_default_is_candidate_only(self):
        self.assertEqual('false', staged.validate_manifest(self.manifest)['publish'])
        self.manifest['tag'] = 'v1.0.1'
        self.assertEqual('v1.0.1', staged.validate_manifest(self.manifest)['tag'])
        self.manifest['publish'] = True
        self.assertEqual('true', staged.validate_manifest(self.manifest)['publish'])

    def test_rejects_unsafe_versions_downgrades_and_unpinned_files(self):
        for field, value in (('version', '../secret'), ('version', '1.0\nother=x'),
                             ('tag', 'v9.0'), ('tag', '../unsafe'), ('versionCode', 100), ('versionCode', True),
                             ('baselineSource', 'main'), ('schema', 3), ('publish', 'false')):
            candidate = copy.deepcopy(self.manifest)
            candidate[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                staged.validate_manifest(candidate)
        self.manifest['sha256']['../key.p12'] = 'c' * 64
        with self.assertRaises(ValueError):
            staged.validate_manifest(self.manifest)

    def test_stable_publication_requires_future_policy_review(self):
        self.manifest.update(version='1.0.1', publish=True)
        self.manifest['sha256']['offgrid-1.0.1.apk'] = self.manifest['sha256'].pop('offgrid-1.0.1-experimental.apk')
        with self.assertRaises(ValueError):
            staged.validate_manifest(self.manifest)

    def test_legacy_manifest_never_publishes_by_default(self):
        legacy = dict(version='1.0', sourceCommit='b' * 40,
                      sha256={name: 'c' * 64 for name in
                              ('offgrid-1.0.apk', 'baseline.apk', 'baseline-test.apk', 'upgrade-test.apk')})
        self.assertEqual('false', staged.validate_manifest(legacy)['publish'])


if __name__ == '__main__':
    unittest.main()
