import http.server,json,os,pathlib,shutil,subprocess,tempfile,threading,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
POWERSHELL=pathlib.Path(os.environ['SystemRoot'])/'System32/WindowsPowerShell/v1.0/powershell.exe'
class LaunchTests(unittest.TestCase):
 def run_launcher(self,version=0,installed=False,fail_import=False,stop=False,active=False,unavailable=False,unicode_path=False):
  self.assertTrue((ROOT/'scripts/launch.ps1').is_file(),'release launch entry is missing')
  with tempfile.TemporaryDirectory(prefix='中文 swapy launch ' if unicode_path else 'swapy launch ') as folder:
   root=pathlib.Path(folder);(root/'scripts').mkdir();(root/'runtime/bin').mkdir(parents=True);(root/'runtime/bin/java.exe').write_bytes(b'test runtime')
   (root/'library').mkdir();csv=b'swapy-library-csv,1,test,5\n';(root/'library/swapy-basic.csv').write_bytes(csv)
   shutil.copyfile(ROOT/'scripts/launch.ps1',root/'scripts/launch.ps1')
   (root/'scripts/start.ps1').write_text('param([int]$Port,[switch]$NoBrowser)\n',encoding='utf-8-sig')
   data=root/'local/Swapy/data' if installed else root/'data'
   if installed:(root/'installed.flag').write_text('installed')
   data.mkdir(parents=True);sentinel=data/'keep.txt';sentinel.write_text('keep existing user data')
   (root/'scripts/stop.ps1').write_text("[IO.File]::WriteAllText((Join-Path $env:SWAPY_DATA 'stopped.txt'),'stopped')",encoding='utf-8-sig')
   calls=[]
   class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):
     if unavailable and self.path.endswith('/session'):self.send_error(503);return
     body={'token':'test-token','backend':'java-sqlite','dataDirectory':str(data)} if self.path.endswith('/session') else {'version':version,'poolCount':0 if not version else 10,'sensitiveCount':0}
     if self.path.endswith('/tasks'):body=[{'status':'RUNNING' if active else 'SUCCEEDED'}]
     self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(json.dumps(body,ensure_ascii=False).encode('utf-8'))
    def do_POST(self):
     body=self.rfile.read(int(self.headers['Content-Length']));calls.append((self.path,self.headers.get('X-Swapy-Token'),body))
     self.send_response(500 if fail_import else 200);self.end_headers();self.wfile.write(b'{}')
   with http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler) as server:
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    env=os.environ.copy();env.pop('SWAPY_DATA',None);env['LOCALAPPDATA']=str(root/'local')
    if stop:(data/'service.json').write_text(json.dumps({'port':server.server_port,'pid':os.getpid()}))
    args=['-Stop','-ForUpgrade'] if stop else []
    result=subprocess.run([str(POWERSHELL),'-NoProfile','-ExecutionPolicy','Bypass','-File',str(root/'scripts/launch.ps1'),'-Port',str(server.server_port),'-NoBrowser']+args,env=env,capture_output=True,timeout=35)
    result.stopped_marker=(data/'stopped.txt').exists()
    server.shutdown()
   self.assertEqual(sentinel.read_text(),'keep existing user data')
   return result,calls,csv
 def test_empty_catalog_bootstraps_from_bundled_csv(self):
  result,calls,csv=self.run_launcher();self.assertEqual(result.returncode,0,result.stderr.decode(errors='replace'));self.assertEqual(len(calls),1);self.assertEqual(calls[0][0],'/api/v1/catalog/imports');self.assertEqual(calls[0][1],'test-token');self.assertIn(csv,calls[0][2]);self.assertNotIn(b'test-token',result.stdout)
 def test_existing_catalog_is_never_overwritten(self):
  result,calls,_=self.run_launcher(version=7);self.assertEqual(result.returncode,0,result.stderr.decode(errors='replace'));self.assertEqual(calls,[])
 def test_utf8_session_path_without_charset_is_supported(self):
  result,calls,_=self.run_launcher(unicode_path=True);self.assertEqual(result.returncode,0,result.stderr.decode(errors='replace'));self.assertEqual(len(calls),1)
 def test_installed_data_is_outside_program_directory(self):
  result,calls,_=self.run_launcher(installed=True);self.assertEqual(result.returncode,0,result.stderr.decode(errors='replace'));self.assertEqual(len(calls),1)
 def test_initial_import_failure_is_not_reported_as_success(self):
  result,calls,_=self.run_launcher(fail_import=True);self.assertNotEqual(result.returncode,0);self.assertEqual(len(calls),1)
 def test_upgrade_refuses_to_interrupt_active_work(self):
  result,calls,_=self.run_launcher(stop=True,active=True);self.assertNotEqual(result.returncode,0);self.assertFalse(result.stopped_marker);self.assertEqual(calls,[])
 def test_upgrade_refuses_a_live_service_with_unknown_task_state(self):
  result,calls,_=self.run_launcher(stop=True,unavailable=True);self.assertNotEqual(result.returncode,0);self.assertFalse(result.stopped_marker);self.assertEqual(calls,[])
if __name__=='__main__':unittest.main()
