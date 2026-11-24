"""Verify the real Windows EXE/ZIP without global Java or optional Office engines."""
import argparse,base64,csv,hashlib,json,os,pathlib,re,sqlite3,subprocess,time,urllib.request,uuid,xml.etree.ElementTree as ET,zipfile

ROOT=pathlib.Path(__file__).resolve().parents[2]
PS=pathlib.Path(os.environ['SystemRoot'])/'System32/WindowsPowerShell/v1.0/powershell.exe'
CMD=pathlib.Path(os.environ['SystemRoot'])/'System32/cmd.exe'
HIDDEN=0x08000000

def run(args,env,timeout=240):
    result=subprocess.run(args,env=env,stdin=subprocess.DEVNULL,capture_output=True,timeout=timeout,creationflags=HIDDEN)
    if result.returncode:raise AssertionError((result.returncode,result.stdout[-2000:].decode(errors='replace'),result.stderr[-2000:].decode(errors='replace')))
    return result.stdout

def ps(script,env):
    script='[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false); '+script
    return run([str(PS),'-NoProfile','-EncodedCommand',base64.b64encode(script.encode('utf-16le')).decode()],env).decode('utf-8-sig').strip()

def command(app,name,env,port):
    text=f'"{CMD}" /d /s /c ""{app/name}" -Port {port} -NoBrowser"'
    logs=pathlib.Path(env.get('SWAPY_DATA') or env['LOCALAPPDATA'])/'launcher-smoke-logs';logs.mkdir(parents=True,exist_ok=True)
    stem=uuid.uuid4().hex;out=logs/(stem+'.out');error=logs/(stem+'.err')
    with out.open('wb') as stdout,error.open('wb') as stderr:
        process=subprocess.Popen(text,env=env,stdin=subprocess.DEVNULL,stdout=stdout,stderr=stderr,creationflags=HIDDEN)
        code=process.wait(timeout=240)
    if code:raise AssertionError((code,out.read_bytes()[-2000:].decode(errors='replace'),error.read_bytes()[-2000:].decode(errors='replace')))
    return out.read_bytes()

def verify_runtime_identity(java):
    expected=json.loads((ROOT/'scripts/release-tools.json').read_text(encoding='utf-8'))
    assert 'Temurin-'+expected['runtime']['version'] in java,java
    return True

def verify_redaction(original,changed,patterns):
    assert any(pattern.search(original) for pattern in patterns),'Fixture must contain a configured sensitive term'
    assert original!=changed,'Redaction left the fixture unchanged'
    assert not any(pattern.search(changed) for pattern in patterns),'Sensitive content remained in redacted output'
    return True

def sensitive_patterns(app):
    result=[]
    with (app/'library/swapy-basic.csv').open(encoding='utf-8-sig',newline='') as stream:
        for row in csv.reader(stream):
            if len(row)!=10 or row[0]!='sensitive' or row[7]!='启用':continue
            result.append(re.compile(r'(?<![A-Za-z0-9_])'+re.escape(row[2])+r'(?![A-Za-z0-9_])',re.I) if '英文' in row[4] else re.compile(re.escape(row[2])))
    return result

class Api:
    def __init__(self,port):
        self.base=f'http://127.0.0.1:{port}/api/v1';self.op=urllib.request.build_opener(urllib.request.ProxyHandler({}));self.session=self.get('/session')
    def get(self,path,body=None):
        headers={}
        if hasattr(self,'session'):headers['X-Swapy-Token']=self.session['token']
        if body is not None:headers['Content-Type']='application/json'
        with self.op.open(urllib.request.Request(self.base+path,None if body is None else json.dumps(body).encode(),headers),timeout=180) as response:return json.load(response)
    def task(self,body,expected=('SUCCEEDED',)):
        identifier=self.get('/tasks',body)['id'];deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            status=self.get('/tasks/'+identifier)
            if status['status'] in ('SUCCEEDED','PARTIAL','FAILED','INTERRUPTED'):
                assert status['status'] in expected,(status,self.get('/tasks/'+identifier+'/issues'))
                return identifier
            time.sleep(.25)
        raise AssertionError(('Task timeout',identifier))

def fixtures(folder):
    folder.mkdir()
    (folder/'演示.md').write_text('军队在指挥所组织电子侦察。ELECTRONIC WARFARE; EW eW. new normal stays.\n',encoding='utf-8')
    (folder/'message.json').write_text(json.dumps({'message':'ELECTRONIC WARFARE; EW eW.'}),encoding='utf-8')
    (folder/'schema.sql').write_text("CREATE TABLE records (emitter_id TEXT);\nSELECT emitter_id FROM records WHERE emitter_id = 'EW';\n",encoding='utf-8')
    with zipfile.ZipFile(folder/'office.docx','w') as archive:
        archive.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        archive.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        archive.writestr('word/document.xml','<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>军队 electronic warfare. EW.</w:t></w:r></w:p></w:body></w:document>')

def body_text(path):
    if path.suffix=='.docx':
        with zipfile.ZipFile(path) as archive:
            root=ET.fromstring(archive.read('word/document.xml'))
            return ''.join(element.text or '' for element in root.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t'))
    if path.suffix=='.json':return json.loads(path.read_text(encoding='utf-8'))['message']
    return path.read_text(encoding='utf-8')

def validate_stage(app):
    for entry in json.loads((app/'files.json').read_text(encoding='utf-8-sig')):
        path=app/entry['path'];assert path.is_file(),path
        assert hashlib.sha256(path.read_bytes()).hexdigest()==entry['sha256'],path
    assert not (app/'data').exists(),'User data must not be packaged'
    assert not (app/'office').exists(),'LibreOffice was explicitly deferred'
    assert json.loads((app/'build.json').read_text(encoding='utf-8-sig'))['officeBundled'] is False
    for name in ('start','stop','launch'):assert (app/f'scripts/{name}.ps1').read_bytes().startswith(b'\xef\xbb\xbf')

def main(manifest):
    print('Validating distribution files',flush=True)
    release=json.loads(manifest.read_text(encoding='utf-8-sig'));stage=pathlib.Path(release['stage']);validate_stage(stage)
    work=ROOT/'dist'/('qa-'+time.strftime('%Y%m%d-%H%M%S'));work.mkdir();inputs=work/'input';fixtures(inputs)
    env=os.environ.copy()
    for name in ('JAVA_HOME','SWAPY_DATA'):env.pop(name,None)
    env['LOCALAPPDATA']=str(work/'local');env['SWAPY_ROOTS']=str(inputs);env['SWAPY_SOFFICE']=str(work/'converter-not-installed.exe')
    env['PATH']=os.pathsep.join(str(pathlib.Path(os.environ['SystemRoot'])/name) for name in ('System32','','System32/Wbem','System32/WindowsPowerShell/v1.0'))
    env['TEMP']=env['TMP']=str(work/'temp');pathlib.Path(env['TEMP']).mkdir()
    install=work/'安装 app';installer=pathlib.Path(release['setup'])
    run(f'"{installer}" /S /NOAUTOSTART /NOINTEGRATION /D={install}',env)
    assert (install/'installed.flag').is_file();note=install/'kept-user-note.txt';note.write_text('user-owned file');started=False
    try:
        print('Checking installed runtime and vocabulary',flush=True)
        command(install,'Start Swapy.cmd',env,4177);started=True;api=Api(4177);data=work/'local/Swapy/data'
        assert pathlib.Path(api.session['dataDirectory']).samefile(data)
        summary=api.get('/catalog/summary');assert summary['version']==5 and summary['poolCount']==54213 and summary['sensitiveCount']==540,summary
        runtime=json.loads(ps("(Get-CimInstance Win32_Process -Filter 'ProcessId="+str(api.session['pid'])+"').ExecutablePath | ConvertTo-Json -Compress",env))
        assert pathlib.Path(runtime).samefile(install/'runtime/bin/java.exe'),runtime
        java=run([str(install/'runtime/bin/java.exe'),'--version'],env).decode(errors='replace');verify_runtime_identity(java)
        original={p.name:p.read_bytes() for p in inputs.iterdir()}
        scan=api.task({'kind':'scan','source':{'rootId':'root0','path':''}})
        redacted=api.task({'kind':'redact','source':{'rootId':'root0','path':''},'mode':'B','filenamePrefix':False})
        credential=api.get('/restore-resolutions',{'taskId':redacted});assert credential['mappings'],'Effective mappings are required'
        restored=api.task({'kind':'restore','source':{'rootId':'output','path':redacted+'/files'},'mode':'B','filenamePrefix':False,'credential':credential})
        output=data/'tasks'/restored/'files';changed=data/'tasks'/redacted/'files';patterns=sensitive_patterns(install)
        for path in inputs.iterdir():
            before=body_text(path);intermediate=body_text(changed/path.name);after=body_text(output/path.name)
            verify_redaction(before,intermediate,patterns);assert before==after,(path.name,before,after)
            assert path.read_bytes()==original[path.name]
        assert (output/'演示.md').read_bytes()==original['演示.md']
        print('MD/DOCX/JSON/SQL redaction and restoration passed',flush=True)
        unsupported=inputs/'unsupported';unsupported.mkdir()
        (unsupported/'sample.rtf').write_text(r'{\rtf1\ansi electronic warfare}',encoding='ascii')
        missing=api.task({'kind':'scan','source':{'rootId':'root0','path':'unsupported'}},expected=('FAILED','PARTIAL'))
        assert 'OFFICE_ENGINE_UNAVAILABLE' in json.dumps(api.get('/tasks/'+missing+'/issues'))
        api.get('/catalog/entries',{'kind':'sensitive','word':'release_keep_marker','pos':'名词','form':'英文标识','role':'标识'})
        command(install,'Stop Swapy.cmd',env,4177);started=False
        command(install,'Start Swapy.cmd',env,4177);started=True;api=Api(4177)
        assert api.get('/catalog/summary')['version']==6
        assert {scan,redacted,restored,missing}<={t['id'] for t in api.get('/tasks')}
        previous_pid=api.session['pid']
        print('Checking running-installation upgrade',flush=True)
        run(f'"{installer}" /S /NOAUTOSTART /NOINTEGRATION /D={install}',env);started=False
        command(install,'Start Swapy.cmd',env,4177);started=True;api=Api(4177)
        assert api.session['pid']!=previous_pid
        assert api.get('/catalog/summary')['version']==6
        assert {scan,redacted,restored,missing}<={t['id'] for t in api.get('/tasks')}
        command(install,'Stop Swapy.cmd',env,4177);started=False
        assert install.resolve().is_relative_to(work.resolve())
        print('Checking uninstall preserves user files and database',flush=True)
        run([str(install/'Uninstall.exe'),'/S'],env)
        deadline=time.monotonic()+90
        while (install/'runtime/bin/java.exe').exists() and time.monotonic()<deadline:time.sleep(.4)
        assert not (install/'runtime/bin/java.exe').exists(),'Uninstall did not finish'
        assert note.read_text()=='user-owned file'
        with sqlite3.connect(data/'swapy.db') as database:assert database.execute('select max(version) from catalog_releases').fetchone()[0]==6
        portable=work/'便携 app';portable.mkdir()
        print('Checking actual portable ZIP startup',flush=True)
        with zipfile.ZipFile(release['zip']) as archive:archive.extractall(portable)
        app=portable/('Swapy-'+release['version']);validate_stage(app);command(app,'Start Swapy.cmd',env,4178)
        try:
            api=Api(4178);assert pathlib.Path(api.session['dataDirectory']).samefile(app/'data')
            assert api.get('/catalog/summary')['version']==5 and api.get('/catalog/summary')['sensitiveCount']==540
        finally:command(app,'Stop Swapy.cmd',env,4178)
        result={'work':str(work),'sourceCommit':json.loads((stage/'build.json').read_text(encoding='utf-8-sig'))['sourceCommit'],'installed':True,'portable':True,'upgraded':True,'mappingCount':len(credential['mappings']),'runtimeVersionValidated':True,'javaVersion':java.strip(),'restartPreservedVersion':6,'uninstallPreservedData':True,'formats':['MD','DOCX','JSON','SQL'],'officeBundled':False,'missingOfficeReported':True,'globalJavaRemovedFromPath':True,'setupSha256':hashlib.sha256(pathlib.Path(release['setup']).read_bytes()).hexdigest(),'zipSha256':hashlib.sha256(pathlib.Path(release['zip']).read_bytes()).hexdigest()}
        (work/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps(result,ensure_ascii=False),flush=True)
    finally:
        if started:command(install,'Stop Swapy.cmd',env,4177)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('manifest',type=pathlib.Path);main(parser.parse_args().manifest)
