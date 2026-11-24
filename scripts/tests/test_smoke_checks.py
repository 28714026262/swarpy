import importlib.util,pathlib,re,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('smoke',ROOT/'scripts/tests/release_smoke.py');smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)
class SmokeChecks(unittest.TestCase):
 def test_original_copy_cannot_be_counted_as_redaction(self):
  patterns=[re.compile(r'\bEW\b',re.I)]
  self.assertTrue(smoke.verify_redaction('EW example','HB example',patterns))
  self.assertRaises(AssertionError,smoke.verify_redaction,'EW example','EW example',patterns)
  self.assertRaises(AssertionError,smoke.verify_redaction,'EW example','EW changed',patterns)
 def test_runtime_identity_must_match_corresponding_sources(self):
  self.assertTrue(smoke.verify_runtime_identity('openjdk version \"17.0.21.1\" Temurin-17.0.21.1+2'))
  self.assertRaises(AssertionError,smoke.verify_runtime_identity,'Oracle Java 17')
