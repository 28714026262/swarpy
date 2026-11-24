import hashlib,importlib.util,json,pathlib,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
class PublishTests(unittest.TestCase):
 def payload(self,dirty=False):
  spec=importlib.util.spec_from_file_location('publish',ROOT/'scripts/publish-release.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
  temp=tempfile.TemporaryDirectory();root=pathlib.Path(temp.name);stage=root/'stage';stage.mkdir()
  (stage/'build.json').write_text(json.dumps({'sourceCommit':'abc','sourceDirty':dirty,'librarySha256':'corpus'}))
  setup=root/'setup.exe';setup.write_bytes(b'installer');portable=root/'portable.zip';portable.write_bytes(b'portable');sources=root/'sources.tar.gz';sources.write_bytes(b'corresponding sources')
  release={'stage':str(stage),'setup':str(setup),'zip':str(portable),'sourceCommit':'abc'}
  config={'library':{'sha256':'corpus'},'runtime':{'sourceSha256':hashlib.sha256(sources.read_bytes()).hexdigest()}}
  return module,temp,release,sources,config
 def test_uncommitted_build_cannot_be_published(self):
  module,temp,release,sources,config=self.payload(dirty=True)
  with temp:self.assertRaises(ValueError,module.validate_payload,release,sources,'abc',config)
 def test_sources_and_commit_are_verified_before_upload(self):
  module,temp,release,sources,config=self.payload()
  with temp:
   self.assertEqual(len(module.validate_payload(release,sources,'abc',config)),3)
   self.assertRaises(ValueError,module.validate_payload,release,sources,'different',config)
   sources.write_bytes(b'wrong source archive')
   self.assertRaises(ValueError,module.validate_payload,release,sources,'abc',config)
 def test_resuming_a_draft_accepts_empty_asset_delete_response(self):
  import io
  module,temp,_,_,_=self.payload()
  with temp:
   client=object.__new__(module.GitHub);response=io.BytesIO(b'');response.status=204
   client.request=lambda *args,**kwargs:response
   self.assertIsNone(client.json('/asset',method='DELETE'))
 def test_no_unverified_draft_asset_can_be_published(self):
  module,temp,_,_,_=self.payload()
  with temp:
   expected={'setup.exe','portable.zip','sources.tar.gz','SHA256SUMS.txt'}
   assets=[{'name':name} for name in expected]
   self.assertTrue(module.validate_asset_set(assets,expected))
   self.assertRaises(ValueError,module.validate_asset_set,assets+[{'name':'old-setup.exe'}],expected)
if __name__=='__main__':unittest.main()
