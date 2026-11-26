import os,sys,subprocess,tempfile,pathlib,time,json,re,urllib.request,signal
root=pathlib.Path.cwd(); jar=pathlib.Path(os.environ.get('KINUVO_JAR',root/'dist/kinuvo.jar')); mode=sys.argv[1]; base=pathlib.Path(tempfile.mkdtemp(prefix='kinuvo-'+mode+'-')); (base/'config.yaml').write_text('tasks:\n  provider: local\n')
import threading,http.server
class Stub(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  b=json.dumps({'object':'list','data':[{'id':'stub','object':'model','owned_by':'spike'}]}).encode();self.send_response(200);self.end_headers();self.wfile.write(b)
 def do_POST(self):
  request=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  (base/'model-request.json').write_text(json.dumps(request))
  if self.path.endswith('/responses'):
   assert request.get('stream') and request['input']
   (base/'responses-request.json').write_text(json.dumps(request))
   self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
   response={'id':'resp_stub','object':'response','created_at':1,'model':'stub','output':[],'status':'completed','parallel_tool_calls':False,'tools':[],'text':{'format':{'type':'text'}}}
   events=[{'type':'response.output_text.delta','item_id':'msg_stub','output_index':0,'content_index':0,'delta':'SPIKE_RESPONSES_REPLY','sequence_number':0}, {'type':'response.completed','sequence_number':1,'response':response}]
   for event in events:self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode())
   return
  self.send_response(200)
  if request.get('stream'):
   self.send_header('Content-Type','text/event-stream');self.end_headers()
   for delta,finish in [({'role':'assistant','content':'SPIKE_REPLY'},None),({},'stop')]:
    self.wfile.write(('data: '+json.dumps({'id':'stub','object':'chat.completion.chunk','created':1,'model':'stub','choices':[{'index':0,'delta':delta,'finish_reason':finish}]})+'\n\n').encode())
   self.wfile.write(b'data: [DONE]\n\n')
  else:
   self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(json.dumps({'id':'stub','object':'chat.completion','created':1,'model':'stub','choices':[{'index':0,'message':{'role':'assistant','content':'SPIKE_REPLY'},'finish_reason':'stop'}],'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}}).encode())
 def log_message(self,*a):pass
stub=http.server.HTTPServer(('127.0.0.1',0),Stub);threading.Thread(target=stub.serve_forever,daemon=True).start()
(base/'config.yaml').write_text('tasks:\n  provider: local\nproviders:\n  spike:\n    baseUrl: http://127.0.0.1:'+str(stub.server_port)+'/v1\n    apiKeyRequired: false\n    models:\n      - id: stub\n        tools: false\n')
(base/'agents/default').mkdir(parents=True)
(base/'agents/default/agent.yaml').write_text('provider: custom/spike\nmodel: stub\ntools: []\n')
env=dict(os.environ,KINUVO_HOME=str(base)); results=[]; traces=[]
graal=os.environ.get('GRAALVM_HOME','')
jbr=os.environ.get('JBR_HOME','')
def prefix():
 if mode=='trace':
  d=base/('trace-'+str(len(traces))); traces.append(str(d))
  return [graal+'/bin/java','--enable-native-access=ALL-UNNAMED','-agentlib:native-image-agent=config-output-dir='+str(d),'-jar',str(jar)]
 if mode=='jvm': return [jbr+'/bin/java','-jar',str(jar)]
 return [str(pathlib.Path(sys.argv[2]).resolve())]
def run(args,input=None,timeout=30):
 start=time.monotonic()
 try:
  command=prefix()
  if args[0] in ['--serialization-probe','--browser-probe','--responses-probe','--webrtc-probe','--voice-worker-probe'] and mode in ['jvm','trace']:
   command=[jbr+'/bin/java','-cp',str(root/'build/native-image/probe')+':'+str(root/'build/native-image/flat/BOOT-INF/classes')+':'+str(root/'build/native-image/flat/BOOT-INF/lib/*')+':'+str(root/'build/native-image/flat'),'NativeCli']
  command_env=dict(env)
  if args[0]=='--voice-worker-probe':command_env.pop('KINUVO_VOICE_WORKER',None)
  p=subprocess.run(command+args,env=command_env,cwd=base,input=input,text=True,capture_output=True,timeout=timeout)
  out=p.stdout+p.stderr; rc=p.returncode
  if "but isn't reachable" in out or 'Error:' in out:rc=1
 except subprocess.TimeoutExpired as e: out='TIMEOUT';rc=124
 results.append(dict(command=' '.join(args),rc=rc,seconds=round(time.monotonic()-start,3),output=out))
 (base/'results.json').write_text(json.dumps(results,indent=2)); return out
def stop(p):
 p.terminate()
 try:p.wait(15)
 except subprocess.TimeoutExpired:p.kill();p.wait()
def serve(extra=[]):
 log=open(base/('serve'+str(len(results))+'.log'),'w')
 p=subprocess.Popen(prefix()+['serve','--port','8798']+extra,env=env,cwd=base,stdout=log,stderr=log)
 start=time.monotonic();ready=False
 for _ in range(120):
  try:
   urllib.request.urlopen('http://127.0.0.1:8798/',timeout=1);ready=True; break
  except Exception:
   if p.poll()!=None:break
   time.sleep(.5)
 if '--tailcat' in extra:
  deadline=time.monotonic()+20
  while time.monotonic()<deadline and p.poll() is None:
   if 'Tailcat is active' in pathlib.Path(log.name).read_text():break
   time.sleep(.5)
 healthy=ready and p.poll() is None and ('--tailcat' not in extra or 'Tailcat is active' in pathlib.Path(log.name).read_text())
 results.append(dict(command='serve '+' '.join(extra),rc=0 if healthy else 1,seconds=round(time.monotonic()-start,3),output=str(log.name)+' rss_kib='+subprocess.run(['ps','-o','rss=','-p',str(p.pid)],capture_output=True,text=True).stdout.strip()))
 return p
run(['--webrtc-probe'])
run(['--voice-worker-probe'])
run(['--serialization-probe'])
run(['--responses-probe','http://127.0.0.1:'+str(stub.server_port)])
# Chrome uses only this disposable home's profile and is terminated by the probe.
run(['--browser-probe'],timeout=75)
if os.getenv('PROBES_ONLY'):
 stub.shutdown();print(base,flush=True);sys.exit(0 if all(r['rc']==0 for r in results) else 1)
# Every actual command/subcommand help, including commands never executed.
groups={'agents':'list show create set delete','routines':'list show create edit pause resume run remove runs','skills':'list show create install remove','mcp':'list add remove test login logout','knowledge':'search show changes undo','tasks':'list lists add assign status','secrets':'status connect disconnect browse refresh','devices':'list revoke','service':'install uninstall restart status logs'}
if not os.getenv('SKIP_HELP'):
 run(['--help'])
 for g,subs in groups.items():
  run([g,'--help'])
  for s in subs.split():run([g,s,'--help'])
 for c in ['serve','login','tailcat-reset','acp','notify','pair']:run([c,'--help'])
server=serve()
try:
 # Fetch every packaged web asset as well as direct index references.
 import zipfile
 with zipfile.ZipFile(jar) as z:
  assets={n[4:]:z.read(n) for n in z.namelist() if n.startswith('web/') and not n.endswith('/')}
 for path in ['',*assets,'agents','settings','sessions']:
  start=time.monotonic()
  try:
   with urllib.request.urlopen('http://127.0.0.1:8798/'+path,timeout=10) as r:data=r.read();rc=r.status
  except Exception as e:rc=str(e);data=b''
  expected=assets.get('index.html' if path=='' else path)
  if expected is not None and data!=expected:rc=1
  results.append(dict(command='GET /'+path,rc=rc,seconds=round(time.monotonic()-start,3),output=str(len(data))+' bytes'))
 for a in [['agents','list'],['agents','create','spike','--clone','default'],['agents','set','spike','name','Trace Agent'],['agents','show','spike']]:run(a)
 for shared in [[],['--shared']]:
  for a in [['skills','create','spike-skill','--description','trace','--body','Reply briefly']+shared,['skills','list'],['skills','show','spike-skill']+shared,['skills','remove','spike-skill']+shared]:run(['-a','spike']+a)
 mcp=base/'mcp.py';mcp.write_text("""import sys,json
for line in sys.stdin:
 try:
  q=json.loads(line)
  if 'id' not in q:continue
  m=q.get('method');r={'protocolVersion':'2024-11-05','capabilities':{'tools':{}},'serverInfo':{'name':'spike','version':'1'}} if m=='initialize' else {'tools':[{'name':'echo','description':'echo','inputSchema':{'type':'object','properties':{}}}]} if m=='tools/list' else {'content':[{'type':'text','text':'hello'}]}
  print(json.dumps({'jsonrpc':'2.0','id':q['id'],'result':r}),flush=True)
 except Exception:pass
""")
 for a in [['mcp','add','spike-mcp','--command',sys.executable,'--',str(mcp)],['mcp','list'],['mcp','test','spike-mcp'],['mcp','remove','spike-mcp']]:run(['-a','spike']+a)
 out=run(['-a','spike','routines','create','spike','Say hello','--every','1d','--paused'])
 match=re.search(r'Created routine ([^.]+)\.',out)
 run(['-a','spike','routines','list'])
 if match:
  id=match[1]
  for cmd in ['show','pause','resume','run','runs','remove']:run(['-a','spike','routines',cmd,id])
 for a in [['knowledge','search','spike'],['knowledge','changes'],['tasks','lists'],['tasks','add','spike task'],['tasks','list']]:out=run(a)
 match=re.search(r'Added (\w+)',next((r['output'] for r in results if r['command']=='tasks add spike task'),''))
 if match:
  run(['tasks','assign',match[1],'spike']);run(['tasks','status',match[1],'done'])
 for a in [['secrets','status'],['secrets','browse'],['notify','spike'],['pair'],['pair','--manage'],['devices','list'],['service','status']]:run(a)
 run(['agents','set','spike','model','stub'])
 import selectors
 acp=subprocess.Popen(prefix()+['acp','--agent','spike'],env=env,cwd=base,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=open(base/'acp.stderr','w'),text=True,bufsize=1)
 import queue
 inbox=queue.Queue()
 def read_acp():
  for line in acp.stdout:inbox.put(line)
 threading.Thread(target=read_acp,daemon=True).start()
 lines=[]
 def exchange(id,method,params):
  acp.stdin.write(json.dumps({'jsonrpc':'2.0','id':id,'method':method,'params':params})+'\n');acp.stdin.flush()
  deadline=time.monotonic()+25
  while time.monotonic()<deadline:
   try:line=inbox.get(timeout=1)
   except queue.Empty:continue
   if not line:break
   lines.append(line)
   try:
    value=json.loads(line)
    if value.get('id')==id:return value
   except:pass
  return {}
 try:
  init=exchange(1,'initialize',{'protocolVersion':2,'capabilities':{},'info':{'name':'spike','version':'1'}})
  session=exchange(2,'session/new',{'cwd':str(base),'mcpServers':[]})
  sid=session.get('result',{}).get('sessionId')
  if sid:exchange(3,'session/prompt',{'sessionId':sid,'prompt':[{'type':'text','text':'Say hello'}]})
  deadline=time.monotonic()+15
  while time.monotonic()<deadline and not any('SPIKE_REPLY' in l for l in lines):
   try:lines.append(inbox.get(timeout=1))
   except queue.Empty:pass
  results.append(dict(command='acp initialize/session-new/model-prompt',rc=0 if any('SPIKE_REPLY' in l for l in lines) else 1,seconds=0,output=''.join(lines)))
 finally:stop(acp)
 run(['agents','delete','spike','--yes'])
finally:stop(server)
run(['tailcat-reset'])
tailcat=serve(['--tailcat']);stop(tailcat)
# Only startup; no device pairing or control.
if __import__('shutil').which('cua-driver'):
 computer=serve(['--computer']);stop(computer)
(base/'results.json').write_text(json.dumps(results,indent=2))
if mode=='trace':(base/'traces.json').write_text(json.dumps(traces))
print(base,flush=True)

stub.shutdown()
expected_missing={'GET /agents','GET /settings','GET /sessions'}
failed=[r for r in results if r['rc'] not in (0,200) and not (r['command'] in expected_missing and r['rc']=='HTTP Error 404: Not Found')]
if failed:print('Failed checks: '+', '.join(r['command'] for r in failed),file=sys.stderr)
sys.exit(1 if failed else 0)
