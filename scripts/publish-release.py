"""Upload verified Windows artifacts as a GitHub draft, verify downloads, then publish."""
import argparse,hashlib,json,os,pathlib,re,subprocess,time,urllib.error,urllib.parse,urllib.request

ROOT=pathlib.Path(__file__).resolve().parents[1]
REPO='28714026262/swarpy'

def sha256(path):
    digest=hashlib.sha256()
    with pathlib.Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()

def validate_payload(release,sources,head,config):
    info=json.loads((pathlib.Path(release['stage'])/'build.json').read_text(encoding='utf-8-sig'))
    if info.get('sourceDirty',True) or info.get('sourceCommit')!=head or release.get('sourceCommit')!=head:
        raise ValueError('Only a clean build of the current commit can be published')
    if info.get('librarySha256')!=config['library']['sha256']:raise ValueError('Unexpected release corpus')
    if sha256(sources)!=config['runtime']['sourceSha256']:raise ValueError('Corresponding JDK sources do not match')
    paths=[pathlib.Path(release['setup']),pathlib.Path(release['zip']),pathlib.Path(sources)]
    if not all(path.is_file() and path.stat().st_size>0 for path in paths):raise ValueError('A release artifact is missing')
    return paths

def validate_asset_set(assets,expected):
    names=[asset['name'] for asset in assets]
    if set(names)!=set(expected) or len(names)!=len(expected):
        raise ValueError('Draft attachment set differs from the verified artifacts')
    return True

class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,request,*args,**kwargs):
        redirected=super().redirect_request(request,*args,**kwargs)
        if redirected and urllib.parse.urlparse(redirected.full_url).hostname!='api.github.com':
            redirected.remove_header('Authorization')
        return redirected

class UploadReader:
    def __init__(self,stream,name):self.stream=stream;self.name=name;self.sent=0;self.last=time.monotonic()
    def read(self,size):
        data=self.stream.read(size);self.sent+=len(data)
        if time.monotonic()-self.last>20:
            print(f'Uploading {self.name}: {self.sent//1048576} MiB',flush=True);self.last=time.monotonic()
        return data

class GitHub:
    def __init__(self,proxy):
        credential=subprocess.run(['git','credential','fill'],input='protocol=https\nhost=github.com\n\n',text=True,capture_output=True,check=True)
        values=dict(line.split('=',1) for line in credential.stdout.splitlines() if '=' in line)
        self.token=values['password']
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({'https':proxy} if proxy else {}),SafeRedirect())
    def request(self,url,body=None,method=None,headers=None):
        if urllib.parse.urlparse(url).hostname not in ('api.github.com','uploads.github.com'):raise ValueError('Unexpected GitHub API host')
        combined={'Authorization':'Bearer '+self.token,'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','User-Agent':'SwapyReleaseBuilder'}
        if headers:combined.update(headers)
        if isinstance(body,(dict,list)):body=json.dumps(body,ensure_ascii=False).encode();combined['Content-Type']='application/json'
        return self.opener.open(urllib.request.Request(url,body,combined,method=method),timeout=600)
    def json(self,path,body=None,method=None):
        with self.request('https://api.github.com'+path,body,method) as response:
            return None if response.status==204 else json.load(response)
    def verify_download(self,asset,expected):
        digest=hashlib.sha256();last=time.monotonic();received=0
        with self.request(asset['url']+'?download=1',headers={'Accept':'application/octet-stream'}) as response:
            for block in iter(lambda:response.read(1024*1024),b''):
                digest.update(block);received+=len(block)
                if time.monotonic()-last>20:
                    print(f'Verifying {asset["name"]}: {received//1048576} MiB',flush=True);last=time.monotonic()
        if digest.hexdigest()!=expected:raise ValueError('Downloaded asset hash mismatch: '+asset['name'])
        print('Verified '+asset['name']+' '+expected,flush=True)

def git(*args,proxy=None):
    command=['git']
    if proxy:command+=['-c','http.proxy='+proxy]
    result=subprocess.run(command+['-C',str(ROOT)]+list(args),text=True,capture_output=True,check=True)
    return result.stdout.strip()

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('manifest',type=pathlib.Path);parser.add_argument('--sources',type=pathlib.Path,required=True)
    parser.add_argument('--verification',type=pathlib.Path,required=True);parser.add_argument('--notes',type=pathlib.Path,required=True)
    parser.add_argument('--proxy',default=os.environ.get('HTTPS_PROXY'));parser.add_argument('--publish',action='store_true')
    args=parser.parse_args()
    if git('status','--porcelain'):raise ValueError('Commit all source changes before publication')
    release=json.loads(args.manifest.read_text(encoding='utf-8-sig'));head=git('rev-parse','HEAD')
    config=json.loads((ROOT/'scripts/release-tools.json').read_text(encoding='utf-8'))
    paths=validate_payload(release,args.sources,head,config)
    version=release['version']
    if not re.fullmatch(r'\d+\.\d+\.\d+([.-][A-Za-z0-9.-]+)?',version):raise ValueError('Invalid release version')
    proof=json.loads(args.verification.read_text(encoding='utf-8'))
    if proof.get('sourceCommit')!=head or proof.get('setupSha256')!=sha256(paths[0]) or proof.get('zipSha256')!=sha256(paths[1]):
        raise ValueError('These exact artifacts have not passed the release smoke test')
    if not all(proof.get(key) for key in ('installed','portable','upgraded','uninstallPreservedData','runtimeVersionValidated')) or proof.get('mappingCount',0)<1:raise ValueError('Incomplete installation verification')
    remote=git('ls-remote','origin','refs/heads/main',proxy=args.proxy).split()[0]
    if remote!=head:raise ValueError('Push this commit to origin/main before publishing')
    tag='v'+version
    tags=git('tag','--list',tag).splitlines()
    if tags:
        if git('rev-list','-n','1',tag)!=head:raise ValueError('Existing tag points to another commit')
    else:git('tag','-a',tag,'-m','Swapy '+version+' Windows release',head)
    git('push','origin','refs/tags/'+tag,proxy=args.proxy)
    hashes={path.name:sha256(path) for path in paths}
    checksums=pathlib.Path(release['checksums']);checksums.write_text(''.join(hashes[path.name]+'  '+path.name+'\n' for path in paths),encoding='utf-8',newline='\n')
    paths.append(checksums);hashes[checksums.name]=sha256(checksums)
    client=GitHub(args.proxy);base='/repos/'+REPO
    try:draft=client.json(base+'/releases/tags/'+tag)
    except urllib.error.HTTPError as error:
        if error.code!=404:raise
        draft=client.json(base+'/releases',{'tag_name':tag,'target_commitish':head,'name':'Swapy '+version+' — Windows 一键安装版','body':args.notes.read_text(encoding='utf-8'),'draft':True,'prerelease':False})
    if not draft['draft']:raise ValueError('This release is already public; published assets will not be overwritten')
    upload=draft['upload_url'].split('{',1)[0]
    assets={asset['name']:asset for asset in client.json(base+'/releases/'+str(draft['id'])+'/assets')}
    for path in paths:
        existing=assets.get(path.name)
        if existing and (existing['size']!=path.stat().st_size or existing.get('digest') not in (None,'','sha256:'+hashes[path.name])):
            client.json(base+'/releases/assets/'+str(existing['id']),method='DELETE');existing=None
        if not existing:
            with path.open('rb') as stream:
                with client.request(upload+'?'+urllib.parse.urlencode({'name':path.name}),UploadReader(stream,path.name),'POST',{'Content-Type':'application/octet-stream','Content-Length':str(path.stat().st_size)}) as response:existing=json.load(response)
        client.verify_download(existing,hashes[path.name])
    validate_asset_set(client.json(base+'/releases/'+str(draft['id'])+'/assets'),set(hashes))
    if args.publish:draft=client.json(base+'/releases/'+str(draft['id']),{'draft':False,'make_latest':'true'},'PATCH')
    result={'url':draft['html_url'],'draft':draft['draft'],'tag':tag,'commit':head,'assets':hashes}
    (args.manifest.parent/'github-release.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False),flush=True)

if __name__=='__main__':main()
