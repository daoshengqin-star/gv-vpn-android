from pathlib import Path
import os,json,hashlib,subprocess,urllib.request,gzip,datetime,signal,traceback,shutil
import argparse
parser=argparse.ArgumentParser(description='Rebuild the exact GV VPN routing assets without changing application files.')
parser.add_argument('--output-dir',default='routing-source-output')
args=parser.parse_args()
r=Path(args.output_dir).resolve();r.mkdir(parents=True,exist_ok=True)
pins=json.loads(Path(__file__).with_name('routing-source-pins.json').read_text());state={'startedUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'steps':[],'appOrNativeSourceChanged':False,'VPNOrAccountUsed':False}
def save():(r/'state.json').write_text(json.dumps(state,indent=2))
def run(args,label,cwd=None):
 state['phase']=label;save()
 with (r/(label+'.log')).open('w') as f:
  p=subprocess.run(args,cwd=cwd,env=e,stdout=f,stderr=subprocess.STDOUT,timeout=1800)
 state['steps'].append({'label':label,'exit':p.returncode});save()
 if p.returncode:raise RuntimeError(label+' exit '+str(p.returncode))
def download(url,target,maxbytes=64*1024*1024,expected=None):
 with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'GV-VPN-source-verification'}),timeout=90) as resp:
  data=resp.read(maxbytes+1)
 if len(data)>maxbytes:raise ValueError('Oversized input')
 sha=hashlib.sha256(data).hexdigest()
 if expected and sha!=expected:raise ValueError('Upstream checksum mismatch')
 target.write_bytes(data)
 return {'url':url,'path':str(target),'bytes':len(data),'sha256':sha}
def varint(data,pos):
 n=0
 for shift in range(0,70,7):
  b=data[pos];pos+=1;n|=(b&127)<<shift
  if not b&128:return n,pos
 raise ValueError('Bad protobuf')
def fields(data):
 pos=0
 while pos<len(data):
  begin=pos;tag,pos=varint(data,pos);wire=tag&7
  if wire==2:
   size,pos=varint(data,pos);value=data[pos:pos+size];pos+=size
  elif wire==0:value,pos=varint(data,pos)
  elif wire in (1,5):
   size=8 if wire==1 else 4;value=data[pos:pos+size];pos+=size
  else:raise ValueError('Unknown protobuf wire')
  if pos>len(data):raise ValueError('Truncated protobuf')
  yield tag>>3,value,data[begin:pos]
def enc(n):
 a=bytearray()
 while n>127:a.append((n&127)|128);n>>=7
 a.append(n);return bytes(a)
def field(n,v):return enc((n<<3)|2)+enc(len(v))+v
def subset(blob,codes):
 selected={}
 for n,p,raw in fields(blob):
  if n!=1:continue
  code=next(v.decode().upper() for t,v,_ in fields(p) if t==1)
  if code in codes:selected[code]=raw
 assert set(selected)==set(codes)
 return b''.join(selected[c] for c in codes)
e=dict(os.environ,GOTOOLCHAIN='local',GOMAXPROCS='4',GOFLAGS='-p=4',GOPATH=str(r/'gopath'),GOCACHE=str(r/'gocache'))
state['environmentBefore']={'route':subprocess.check_output(['ip','route'],text=True),'resolvConfSHA256':hashlib.sha256(Path('/etc/resolv.conf').read_bytes()).hexdigest()}
save()
try:
 run(['go','version'],'go-version')
 assert 'go1.27.2' in (r/'go-version.log').read_text(),'Use the documented Go1.27.2 toolchain.'
 for k in ['dlc','geoip']:
  p=pins[k];d=r/k
  if not d.exists():run(['git','clone','--depth','1','--branch',p['tag'],'https://github.com/'+p['repo']+'.git',str(d)],k+'-clone')
  head=subprocess.check_output(['git','-C',str(d),'rev-parse','HEAD'],text=True).strip();assert head==p['commit'],head
  state[k+'SourceCommit']=head;save()
 (r/'dlc-built').mkdir(exist_ok=True)
 run(['go','run','./','--outputdir='+str(r/'dlc-built'),'--exportlists=category-ads-all,tld-cn,cn,tld-!cn,geolocation-!cn,apple,icloud'],'dlc-build',r/'dlc')
 built=(r/'dlc-built/dlc.dat').read_bytes();state['dlcFullSHA256']=hashlib.sha256(built).hexdigest();state['dlcFullMatchesOriginalUpstream']=state['dlcFullSHA256']==pins['dlc']['upstreamSHA256'];save()
 original=download(pins['dlc']['asset']['browser_download_url'],r/'dlc-original.dat',expected=pins['dlc']['upstreamSHA256']);state['dlcOriginalDownload']=original;save()
 assert built==(r/'dlc-original.dat').read_bytes(),'Full DLC source regeneration differs'
 base=subset(built,['GEOLOCATION-!CN','CN'])
 assert hashlib.sha256(base).hexdigest()=='06c6aa693b2e5e5621125e7d384e28c12cb61e04f2ac4fd845f1efc027d83764'
 cn=next(p for n,p,_ in fields(base) if n==1 and next(v.decode().upper() for t,v,_ in fields(p) if t==1)=='CN')
 kept=[];removed=[]
 for n,p,raw in fields(cn):
  if n!=2:continue
  values={t:v for t,v,_ in fields(p) if t in (1,2)}
  if values.get(1)==2 and values.get(2)==b'cn':removed.append('domain:cn')
  else:kept.append(raw)
 assert removed==['domain:cn']
 final=base+field(1,field(1,b'CNEXPLICIT')+b''.join(kept))
 assert hashlib.sha256(final).hexdigest()=='d61e22b705f582750041ca8556921c60405679e57cde350f3d365a79e8234675'
 (r/'geosite-regenerated.dat').write_bytes(final)
 state['geosite']={'bytes':len(final),'sha256':hashlib.sha256(final).hexdigest(),'matchesCandidate':True,'editableSourceRebuilt':True,'originalCNCount':len(kept)+1,'CNExplicitCount':len(kept),'removed':removed};save()
 db=r/'geoip/db-ip';db.mkdir(exist_ok=True)
 state['phase']='download-pinned-month-dbip-input';save()
 downloaded=download(pins['geoip']['inputURL'],r/'dbip-country-lite-2026-10.mmdb.gz',expected=pins['geoip']['inputSHA256'])
 raw=gzip.decompress((r/'dbip-country-lite-2026-10.mmdb.gz').read_bytes());assert hashlib.sha256(raw).hexdigest()==pins['geoip']['uncompressedInputSHA256'];(db/'dbip-country-lite.mmdb').write_bytes(raw)
 downloaded['uncompressedBytes']=len(raw);downloaded['uncompressedSHA256']=hashlib.sha256(raw).hexdigest();state['dbipSource']=downloaded;save()
 run(['go','run','./','-c','./config.json'],'geoip-build',r/'geoip')
 blob=(r/'geoip/output/geoip.dat').read_bytes();state['geoipFullSHA256']=hashlib.sha256(blob).hexdigest();state['geoipFullMatchesOriginalUpstream']=state['geoipFullSHA256']==pins['geoip']['upstreamSHA256'];save()
 original=download(pins['geoip']['asset']['browser_download_url'],r/'geoip-original.dat',expected=pins['geoip']['upstreamSHA256']);state['geoipOriginalDownload']=original;save()
 assert blob==(r/'geoip-original.dat').read_bytes(),'Full GeoIP regeneration differs'
 result=subset(blob,['CN']);assert hashlib.sha256(result).hexdigest()=='95755557037a48b2cc6ab22f860d728c51278dc16472bf1997e85eb95112596e'
 assert result==(r/'geoip/output/cn.dat').read_bytes()
 (r/'geoip-regenerated.dat').write_bytes(result)
 state['geoip']={'bytes':len(result),'sha256':hashlib.sha256(result).hexdigest(),'matchesCandidate':True,'upstreamInputRegenerated':True,'sourceInput':'DB-IP Country Lite October2026 MMDB through exact pinned upstream source'}
 state.update(complete=True,exit=0,phase='complete')
except Exception as ex:
 state.update(complete=True,exit=1,error=str(ex),traceback=traceback.format_exc());print(traceback.format_exc(),flush=True)
state['environmentAfter']={'route':subprocess.check_output(['ip','route'],text=True),'resolvConfSHA256':hashlib.sha256(Path('/etc/resolv.conf').read_bytes()).hexdigest()}
state['environmentUnchanged']=state['environmentBefore']==state['environmentAfter'];state['finishedUTC']=datetime.datetime.now(datetime.timezone.utc).isoformat();save();print(json.dumps(state,indent=2),flush=True)
raise SystemExit(state.get('exit',1))
