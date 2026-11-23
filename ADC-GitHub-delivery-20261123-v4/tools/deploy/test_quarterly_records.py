import hashlib,importlib.util,json,tempfile,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('installer',Path(__file__).with_name('update-quarterly-records.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class InstallerTests(unittest.TestCase):
 def package(self,root):
  entries=[]
  for name in ['install.py','backend.jar','staff/index.html','staff/assets/index-test.js','RUNBOOK.md']:
   p=root/name;p.parent.mkdir(exist_ok=True,parents=True);p.write_bytes(b'TEST file');entries.append({'file':name,'sha256':m.digest(p)})
  (root/'manifest.json').write_text(json.dumps({'entries':entries}),encoding='utf-8');return entries
 def test_valid_manifest_and_modified_payload(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);self.package(root);self.assertEqual(len(m.verify(root)),5);(root/'backend.jar').write_bytes(b'changed')
   with self.assertRaisesRegex(ValueError,'HASH'):m.verify(root)
 def test_duplicate_and_path_escape_are_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);entries=self.package(root);entries.append(entries[0]);(root/'manifest.json').write_text(json.dumps({'entries':entries}))
   with self.assertRaisesRegex(ValueError,'CONTENTS'):m.verify(root)
   entries.pop();entries.append({'file':'../outside','sha256':'fake'});(root/'manifest.json').write_text(json.dumps({'entries':entries}))
   with self.assertRaisesRegex(ValueError,'PATH'):m.verify(root)
 def test_preflight_stops_unknown_version_without_changes(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);self.package(root);before=(root/'backend.jar').read_bytes()
   with self.assertRaisesRegex(ValueError,'VERSION'):m.preflight(root)
   self.assertEqual((root/'backend.jar').read_bytes(),before)
 def test_preflight_requires_matching_base_and_no_pending(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);self.package(root);previous=(m.PREVIOUS_JAR,m.PREVIOUS_INDEX)
   try:
    m.PREVIOUS_JAR=m.digest(root/'backend.jar');m.PREVIOUS_INDEX=m.digest(root/'staff/index.html');self.assertEqual(m.preflight(root)[0],root/'backend.jar');(root/'backend.jar.quarterly-records.pending').write_bytes(b'TEST')
    with self.assertRaisesRegex(ValueError,'PENDING'):m.preflight(root)
   finally:m.PREVIOUS_JAR,m.PREVIOUS_INDEX=previous
if __name__=='__main__':unittest.main()
