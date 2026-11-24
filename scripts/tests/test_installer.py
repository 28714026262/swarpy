import os,pathlib,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
class InstallerTests(unittest.TestCase):
 def test_extraction_failure_never_marks_installation_complete(self):
  compiler=pathlib.Path(os.environ.get('SWAPY_NSIS',ROOT/'backend/target/release-tools/nsis/nsis-3.13/makensis.exe'))
  self.assertTrue(compiler.is_file(),'Prepare the pinned NSIS compiler first')
  parent=ROOT/'backend/target';parent.mkdir(exist_ok=True)
  with tempfile.TemporaryDirectory(prefix='installer-error-',dir=parent) as folder:
   root=pathlib.Path(folder).resolve();self.assertTrue(root.is_relative_to(parent.resolve()))
   target=root/'app';stage=root/'stage';stage.mkdir();listing=root/'uninstall.nsh';listing.write_text('')
   source=(ROOT/'scripts/installer.nsi').read_text(encoding='utf-8-sig')
   source=source.replace('Function .onInit','Function .onInit\n  StrCpy $INSTDIR "'+str(target)+'"',1)
   source=source.replace('File /r "${STAGE_PATH}\\*"','SetErrors')
   self.assertNotIn('File /r "${STAGE_PATH}\\*"',source)
   script=root/'fault.nsi';script.write_text(source,encoding='utf-8-sig');setup=root/'fault.exe'
   built=subprocess.run([str(compiler),'/V1','/DVERSION=0.0.0','/DSTAGE_PATH='+str(stage),'/DOUTPUT_FILE='+str(setup),'/DUNINSTALL_LIST='+str(listing),str(script)],capture_output=True,timeout=45)
   self.assertEqual(built.returncode,0,built.stderr.decode(errors='replace'))
   result=subprocess.run([str(setup),'/S','/NOAUTOSTART','/NOINTEGRATION'],capture_output=True,timeout=30)
   self.assertNotEqual(result.returncode,0)
   self.assertFalse((target/'installed.flag').exists())
