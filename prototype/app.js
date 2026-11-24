(function () {
  'use strict';
  var h = React.createElement;
  var useState = React.useState, useEffect = React.useEffect;
  var A = antd, Button = A.Button, Table = A.Table, Tag = A.Tag, Input = A.Input,
      Select = A.Select, Radio = A.Radio, Switch = A.Switch, Tabs = A.Tabs,
      Drawer = A.Drawer, Upload = A.Upload, Alert = A.Alert, Progress = A.Progress,
      Steps = A.Steps, Modal = A.Modal, Form = A.Form, Space = A.Space,
      Descriptions = A.Descriptions, Tooltip = A.Tooltip;
  var ICONS = {
    workspace: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
    tasks: 'M8 4h12v17H4V4h4 M9 2h6v4H9z M8 11h8 M8 16h6',
    library: 'M4 3h5v18H4z M10 3h5v18h-5z M17 4l4 16 M16 5l3-1 M20 20l3-1',
    settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M10 2h4l1 3 3 2 3 1v4l-3 1-2 3-1 3h-4l-1-3-3-2-3-1V9l3-1 2-3z',
    upload: 'M12 16V3 M7 8l5-5 5 5 M4 15v5h16v-5',
    folder: 'M3 6h7l2 3h9v11H3z M3 6V4h7l2 2h9v3',
    file: 'M6 2h8l5 5v15H6z M14 2v6h5 M9 13h7 M9 17h5',
    scan: 'M8 3H3v5 M16 3h5v5 M21 16v5h-5 M8 21H3v-5 M6 12h12',
    swap: 'M4 7h15 M15 3l4 4-4 4 M20 17H5 M9 13l-4 4 4 4',
    restore: 'M4 9a8 8 0 1 1 1 9 M4 3v6h6 M12 7v5l3 2',
    search: 'M10.5 3a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15 M16 16l5 5',
    plus: 'M12 5v14 M5 12h14',
    download: 'M12 3v13 M7 11l5 5 5-5 M4 17v4h16v-4',
    sync: 'M20 8a8 8 0 0 0-14-3L3 8 M3 3v5h5 M4 16a8 8 0 0 0 14 3l3-3 M21 21v-5h-5',
    chevron: 'M9 5l7 7-7 7', left: 'M15 5l-7 7 7 7',
    check: 'M5 12l4 4L19 6', info: 'M12 11v6 M12 7h.01 M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20',
    book: 'M12 5C8 2 3 3 3 3v17s5-1 9 2c4-3 9-2 9-2V3s-5-1-9 2v17',
    archive: 'M3 3h18v5H3z M5 8v13h14V8 M10 12h4',
    arrow: 'M5 12h14 M14 7l5 5-5 5'
  };
  function Icon(p) { return h('svg', {className:'svg-icon '+(p.className||''), viewBox:'0 0 24 24', fill:'none', stroke:'currentColor', strokeWidth:1.65, strokeLinecap:'round', strokeLinejoin:'round', 'aria-hidden':'true'}, h('path',{d:ICONS[p.name]||ICONS.file})); }
  function icon(name) { return h(Icon,{name:name}); }
  function Brand() { return h('div',{className:'brand'},h('span',{className:'brand-mark'},icon('swap')),h('span',{className:'brand-text'},'Swapy')); }
  function escapeHTML(v) { return String(v == null ? '' : v).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];}); }
  function download(name, body, type) {
    var url=URL.createObjectURL(new Blob([body],{type:type||'text/plain;charset=utf-8'}));
    var a=document.createElement('a'); a.href=url; a.download=name; document.body.appendChild(a); a.click(); a.remove(); setTimeout(function(){URL.revokeObjectURL(url);},1000);
  }
  var sampleFiles = [
    {key:'1',name:'项目说明.md',path:'项目文档',size:'18 KB',hits:5,ext:'md',state:'已完成',preview:'辰星计划由华星科技负责实施，项目负责人为张明。交付资料须按项目约定管理。'},
    {key:'2',name:'客户清单.docx',path:'业务资料',size:'246 KB',hits:12,ext:'docx',state:'已完成',preview:'客户：华星科技\n联系人：张明\n合作地点：海州市'},
    {key:'3',name:'业务字段.sql',path:'数据结构',size:'12 KB',hits:8,ext:'sql',state:'已完成',preview:'CREATE TABLE customer ("华星客户编号" VARCHAR(32));\nSELECT "华星客户编号" FROM customer;'},
    {key:'4',name:'应用配置.json',path:'配置文件',size:'4 KB',hits:4,ext:'json',state:'已完成',preview:'{\n  "company": "华星科技",\n  "project": "辰星计划"\n}'},
    {key:'5',name:'会议纪要.pdf',path:'项目文档',size:'658 KB',hits:6,ext:'pdf',state:'部分脱敏',reason:'“辰星计划”暂无满足约束的项目代号候选，已保留该处原词。',preview:'会议讨论辰星计划阶段安排。参会人员：张明。'},
    {key:'6',name:'使用说明.md',path:'',size:'6 KB',hits:0,ext:'md',state:'未命中',preview:'此目录用于存放项目文档。请保留各任务的独立输出目录。'},
    {key:'7',name:'采购记录.docx',path:'业务资料',size:'198 KB',hits:0,ext:'docx',state:'处理失败',reason:'文档结构损坏，无法正常读取。未生成输出副本。',preview:''}
  ];
  var maps = [
    {key:'m1',sourceId:'S001',original:'华星科技',replacement:'杉屿科技',role:'机构名'},
    {key:'m2',sourceId:'S002',original:'张明',replacement:'陆承',role:'人名'},
    {key:'m3',sourceId:'S003',original:'海州市',replacement:'岚川市',role:'地名'},
    {key:'m4',sourceId:'S006',original:'华星客户编号',replacement:'杉屿客户编号',role:'字段名'}
  ];
  var initialTasks = [
    {id:'T20261123-003',name:'项目交付资料',op:'脱敏',mode:'A',status:'部分完成',time:'今天 14:32',files:sampleFiles,version:12,label:true},
    {id:'T20261123-002',name:'合同归档检查',op:'扫描',mode:'A',status:'已完成',time:'今天 11:08',files:sampleFiles.slice(0,4),version:12,label:true},
    {id:'T20261122-001',name:'供应商资料还原',op:'还原',mode:'B',status:'已完成',time:'昨天 16:45',files:sampleFiles.slice(0,3),version:11,label:true}
  ];
  var baseWords = [
    {id:'S001',word:'华星科技',role:'机构名',form:'中文词',source:'用户手填',status:'启用'},
    {id:'S002',word:'张明',role:'人名',form:'中文词',source:'用户手填',status:'启用'},
    {id:'S003',word:'海州市',role:'地名',form:'中文词',source:'内置词典',status:'启用'},
    {id:'S004',word:'辰星计划',role:'项目代号',form:'中文代号',source:'用户手填',status:'启用'},
    {id:'S005',word:'ABC',role:'产品名',form:'英文缩写',source:'用户手填',status:'启用'},
    {id:'S006',word:'华星客户编号',role:'字段名',form:'中文词',source:'用户手填',status:'启用'},
    {id:'S000',word:'华星旧称',role:'机构名',form:'中文词',source:'用户手填',status:'弃用'}
  ];
  var poolWords = [
    {id:'R001',word:'杉屿科技',role:'机构名',form:'中文词',source:'内置候选',status:'启用'},
    {id:'R002',word:'陆承',role:'人名',form:'中文词',source:'内置候选',status:'启用'},
    {id:'R003',word:'岚川市',role:'地名',form:'中文词',source:'规则生成',status:'启用'},
    {id:'R004',word:'杉屿客户编号',role:'字段名',form:'中文词',source:'规则生成',status:'启用'},
    {id:'R000',word:'林舟',role:'人名',form:'中文词',source:'内置候选',status:'弃用'}
  ];
  var opNames={scan:'扫描',redact:'脱敏',restore:'还原'};
  function statusTag(s) { var colors={'已完成':'success','未命中':'default','已复制':'default','部分脱敏':'warning','部分完成':'warning','处理中':'processing','处理失败':'error','已跳过':'default','中断':'default','启用':'success','弃用':'default'}; return h(Tag,{color:colors[s]||'default'},s); }
  function fileCell(f,onClick) { return h('div',{className:'file-main'},h('span',{className:'file-symbol'},icon(f.ext==='zip'?'archive':'file')),h('div',null,h('button',{className:'filename-link file-name',onClick:function(){onClick(f);}},f.name),h('div',{className:'file-path'},f.path||'根目录'))); }

  var X=SwapyTransfer;
  function App(){
    var useRef=React.useRef;
    var pg=useState('scan'),page=pg[0],setPage=pg[1];
    var js=useState(function(){try{var restored=JSON.parse(localStorage.getItem('swapy.parallel.jobs'))||{};Object.keys(restored).forEach(function(k){restored[k].workers=4;});return restored;}catch(e){return {};}}),jobs=js[0],setJobs=js[1];
    var ins=useState(function(){return {scan:jobs.scan?jobs.scan.input:'',redact:jobs.redact?jobs.redact.input:'项目交付资料',restore:jobs.restore?jobs.restore.input:'脱敏资料'};}),inputs=ins[0],setInputs=ins[1];
    var out=useState(function(){function root(k,fallback){var job=jobs[k];return !job?fallback:job.outputRoot||(job.output||'').slice(0,(job.output||'').lastIndexOf('/'))||fallback;}return {redact:root('redact','/data/tasks'),restore:root('restore','/data/restored')};}),outputs=out[0],setOutputs=out[1];
    var src=useState({redact:sampleFiles,restore:sampleFiles}),sources=src[0],setSources=src[1];
    var md=useState(jobs.redact?jobs.redact.mode:'A'),mode=md[0],setMode=md[1];
    var cr=useState(jobs.restore&&jobs.restore.credential?jobs.restore.credential:''),credential=cr[0],setCredential=cr[1];
    var concurrency=4;
    var savedCatalog=useState(function(){try{var raw=JSON.parse(localStorage.getItem('swapy.catalog'));return raw?X.library(raw):null;}catch(e){return null;}})[0];
    var lv=useState(savedCatalog?savedCatalog.version:12),version=lv[0],setVersion=lv[1];
    var hist=useState(function(){try{return JSON.parse(localStorage.getItem('swapy.parallel.history'))||initialTasks;}catch(e){return initialTasks;}}),historyTasks=hist[0],setHistoryTasks=hist[1];
    var det=useState(null),detail=det[0],setDetail=det[1];
    var wordsState=useState(savedCatalog?savedCatalog.sensitive:baseWords),words=wordsState[0],setWords=wordsState[1];
    var pw=useState(savedCatalog?savedCatalog.pool:poolWords),pool=pw[0],setPool=pw[1];
    var lib=useState('sensitive'),libraryTab=lib[0],setLibraryTab=lib[1];
    var add=useState(false),adding=add[0],setAdding=add[1];
    var formPair=Form.useForm(),wordForm=formPair[0];
    var registryState=useState(function(){try{var stored=JSON.parse(localStorage.getItem('swapy.redaction.registry'));if(stored)return X.mergeRecords({},Object.keys(stored).map(function(k){return stored[k];}));}catch(e){}var data={};historyTasks.concat(Object.keys(jobs).map(function(k){return jobs[k];})).forEach(function(task){if(X.ready(task))data[task.id]=X.record(task,task.mappings||mockMappings(task));});return data;}),registry=registryState[0],setRegistry=registryState[1];
    var tasksRef=useRef([]);tasksRef.current=allTasks();var registryRef=useRef(registry);registryRef.current=registry;var catalogRef=useRef(catalog());catalogRef.current=catalog();
    var cm=useState('id'),credentialMode=cm[0],setCredentialMode=cm[1];
    var uf=useState(''),uploadedPackage=uf[0],setUploadedPackage=uf[1];
    var ct=useState(false),credentialTouched=ct[0],setCredentialTouched=ct[1];
    var ts=useState([]),selectedTasks=ts[0],setSelectedTasks=ts[1];
    var tb=useState(false),transferBusy=tb[0],setTransferBusy=tb[1];
    var libraryInput=useRef(null),packageInput=useRef(null),packagePurpose=useRef('tasks');
    useEffect(function(){try{localStorage.setItem('swapy.catalog',JSON.stringify(catalog()));}catch(e){A.message.error('词库保存失败');}},[words,pool,version]);
    useEffect(function(){try{localStorage.setItem('swapy.redaction.registry',JSON.stringify(registry));}catch(e){A.message.error('脱敏记录保存失败');}},[registry]);
    useEffect(function(){Object.keys(jobs).forEach(function(k){var task=jobs[k];if(X.ready(task))setRegistry(function(old){if(old[task.id])return old;var next=Object.assign({},old);next[task.id]=X.record(task,task.mappings||mockMappings(task));registryRef.current=next;return next;});});},[jobs]);
    function catalog(){return {format:'swapy-library',formatVersion:1,version:version,sensitive:words,pool:pool};}
    function mockMappings(task){return maps.filter(function(m){return (task.files||[]).some(function(f){return !f.local&&f.state!=='处理失败'&&(f.preview||'').indexOf(m.original)>-1;});}).map(function(m){return {sourceId:m.sourceId,original:m.original,replacement:m.replacement+(task.mode==='A'?'〔'+m.sourceId+'〕':'')};});}
    function credentialProblem(value){var key=(value===undefined?credential:value).trim();if(!key)return '请上传密码包或输入任务 ID';var item=Object.prototype.hasOwnProperty.call(registry,key)?registry[key]:null;if(!item){if(allTasks().some(function(t){return t.id===key&&t.op!=='脱敏';}))return '该任务不是脱敏任务';return '本地未找到脱敏任务：'+key;}if(version<item.task.version)return '本地词库 V'+version+' 低于任务所需 V'+item.task.version+'，请先同步或导入词库';return '';}
    function credentialControl(busy){var problem=credentialProblem(),localRecord=Object.prototype.hasOwnProperty.call(registry,credential.trim())?registry[credential.trim()]:null;return h('div',{className:'credential-block'},h('div',{className:'credential-row'},h(Radio.Group,{value:credentialMode,disabled:busy,onChange:function(e){setCredentialMode(e.target.value);setCredentialTouched(false);setCredential('');setUploadedPackage('');}},h(Radio.Button,{value:'file'},'上传密码包'),h(Radio.Button,{value:'id'},'任务 ID')),credentialMode==='file'?h(React.Fragment,null,h(Button,{disabled:busy,onClick:function(){openPackage('restore');}},'选择密码包'),uploadedPackage?h('span',{className:'small'},uploadedPackage):null):h(Input,{value:credential,disabled:busy,onChange:function(e){setCredential(e.target.value);setCredentialTouched(true);},onBlur:function(){setCredentialTouched(true);},placeholder:'输入脱敏任务 ID','aria-label':'还原任务 ID',style:{width:330}})),credentialTouched&&problem?h(Alert,{type:'warning',message:problem,showIcon:true,style:{marginTop:12},action:localRecord&&version<localRecord.task.version?h(Button,{size:'small',onClick:function(){setPage('library');}},'前往词库'):null}):null,busy&&problem?h(Button,{style:{marginTop:12},onClick:function(){setJobs(function(old){var next=Object.assign({},old);delete next.restore;return next;});setCredential('');setCredentialTouched(false);}},'新建还原'):null);}
    function readBytes(file){return new Promise(function(resolve,reject){var r=new FileReader();r.onload=function(){resolve(r.result);};r.onerror=function(){reject(new Error('文件读取失败'));};r.readAsArrayBuffer(file);});}
    function exportLibrary(){try{download('Swapy-词库-V'+version+'.json',JSON.stringify(X.library(catalog()),null,2),'application/json;charset=utf-8');}catch(e){A.message.error(e.message);}}
    function importLibrary(event){var file=event.target.files&&event.target.files[0];if(!file)return;setTransferBusy(true);readBytes(file).then(function(bytes){var raw=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));var merged=X.mergeLibrary(catalogRef.current,raw);ReactDOM.unstable_batchedUpdates(function(){setWords(merged.sensitive);setPool(merged.pool);setVersion(merged.version);});A.message.success('词库已导入，历史 ID 保留');}).catch(function(e){A.message.error(e.message||'词库导入失败');}).finally(function(){setTransferBusy(false);event.target.value='';});}
    function openPackage(purpose){packagePurpose.current=purpose;packageInput.current.value='';packageInput.current.click();}
    function importPackages(event){var file=event.target.files&&event.target.files[0];if(!file)return;var purpose=packagePurpose.current;setTransferBusy(true);readBytes(file).then(X.unpack).then(function(records){var next=X.mergeRecords(registryRef.current,records,tasksRef.current);registryRef.current=next;setRegistry(next);if(purpose==='restore'){if(records.length===1){setCredentialMode('file');setUploadedPackage(file.name);setCredential(records[0].task.id);setCredentialTouched(true);if(version<records[0].task.version)A.message.warning('词库版本过低，请先同步或导入词库');else A.message.success('密码包已载入');}else{setCredentialMode('id');setCredential('');setUploadedPackage('');setCredentialTouched(false);A.message.info('已导入 '+records.length+' 个脱敏任务，请输入要还原的任务 ID');}}else A.message.success('已导入 '+records.length+' 个脱敏任务');}).catch(function(e){A.message.error(e.message||'脱敏任务导入失败');}).finally(function(){setTransferBusy(false);});}
    function exportPackages(ids){var items=ids.map(function(id){return registry[id];}).filter(Boolean);if(!items.length){A.message.info('没有可导出的脱敏任务');return;}setTransferBusy(true);var work=items.length===1?X.pack(items[0]):X.exportMany(items);work.then(function(bytes){download(items.length===1?items[0].task.id+'.swapy':'Swapy-脱敏密码包合集.zip',bytes,'application/zip');}).catch(function(e){A.message.error(e.message||'密码包导出失败');}).finally(function(){setTransferBusy(false);});}
    function addWord(){wordForm.validateFields().then(function(v){try{var value=v.word.trim();if(!value)throw new Error('请输入词条');var newId=X.uniqueWordId(libraryTab==='sensitive'?'S':'R',catalogRef.current.sensitive.concat(catalogRef.current.pool),localStorage,window.crypto);var setter=libraryTab==='sensitive'?setWords:setPool;ReactDOM.unstable_batchedUpdates(function(){setter(function(old){return old.map(function(w){return w.word===value&&w.status==='启用'?Object.assign({},w,{status:'弃用'}):w;}).concat([{id:newId,word:value,role:v.role,form:'中文词',source:'用户手填',status:'启用'}]);});setVersion(function(n){return n+1;});setAdding(false);wordForm.resetFields();});}catch(e){A.message.error(e.message);}});}

    var pending=useRef('scan'),folderInput=useRef(null),fileInput=useRef(null);
    useEffect(function(){document.body.className='parallel-app';},[]);
    useEffect(function(){try{localStorage.setItem('swapy.parallel.jobs',JSON.stringify(jobs));}catch(e){}},[jobs]);
    useEffect(function(){var timer=setInterval(function(){setJobs(function(previous){var changed=false,next=Object.assign({},previous);Object.keys(previous).forEach(function(key){var j=previous[key];if(j.status!=='running')return;changed=true;if(j.phase==='parse'){next[key]=Object.assign({},j,{parseProgress:Math.min(100,j.parseProgress+10),phase:j.parseProgress>=90?'scan':'parse'});}
      else{var completed=Math.min(j.total,j.completed+j.workers);next[key]=Object.assign({},j,{completed:completed,status:completed>=j.total?'done':'running',finishedAt:completed>=j.total?Date.now():null});}});return changed?next:previous;});},1000);return function(){clearInterval(timer);};},[]);
    useEffect(function(){Object.keys(jobs).forEach(function(key){var j=jobs[key];if(j.status==='done')setHistoryTasks(function(old){if(old.some(function(x){return x.id===j.id;}))return old;return [Object.assign({},j,{status:'已完成'})].concat(old);});});},[jobs]);
    useEffect(function(){try{localStorage.setItem('swapy.parallel.history',JSON.stringify(historyTasks));}catch(e){}},[historyTasks]);
    function changeInput(key,value){setInputs(function(old){var n=Object.assign({},old);n[key]=value;return n;});}
    function changeOutput(key,value){setOutputs(function(old){var n=Object.assign({},old);n[key]=value;return n;});}
    function launch(key,files,path){files=files.map(function(f){var copy=Object.assign({},f);if(key==='scan'&&copy.state==='部分脱敏'){delete copy.reason;copy.state='已完成';}return copy;});if(jobs[key]&&(jobs[key].status==='running'||jobs[key].status==='paused'))return;if(key==='restore'){var problem=credentialProblem();if(problem){setCredentialTouched(true);A.message.warning(problem);return;}}var id;try{id=X.uniqueWordId('T',allTasks(),localStorage,window.crypto);}catch(e){A.message.error(e.message);return;}setJobs(function(old){var n=Object.assign({},old);n[key]={id:id,op:opNames[key],name:path||inputs[key],input:path||inputs[key],output:key==='scan'?'':outputs[key]+'/'+id,outputRoot:key==='scan'?'':outputs[key],credential:key==='restore'?credential:'',files:files,mode:key==='restore'?registry[credential.trim()].task.mode:mode,version:version,status:'running',phase:'parse',parseProgress:0,total:files.length,completed:0,workers:4,startedAt:Date.now(),demo:true,mappings:key==='restore'?registry[credential.trim()].mappings:mockMappings({files:files,mode:mode})};return n;});}
    function chooseFolder(key){pending.current=key;folderInput.current.value='';folderInput.current.click();}
    function chooseFiles(key){pending.current=key;fileInput.current.value='';fileInput.current.click();}
    function picked(event,isFolder){var all=Array.prototype.slice.call(event.target.files||[]);if(!all.length)return;var key=pending.current;var folder=(all[0].webkitRelativePath||all[0].name).split('/')[0];var list=all.map(function(f,i){var path=f.webkitRelativePath||f.name;return {key:'local-'+i,name:f.name,path:path.slice(0,Math.max(0,path.lastIndexOf('/'))),ext:f.name.split('.').pop().toLowerCase(),size:Math.max(1,Math.round(f.size/1024))+' KB',hits:0,state:'已完成',preview:'',local:true,reason:'未执行内容处理（原型）'};});changeInput(key,isFolder?folder:all.map(function(f){return f.name;}).join('、'));setSources(function(old){var n=Object.assign({},old);n[key]=list;return n;});}
    function pause(key){if(key==='restore'&&jobs[key].status==='paused'){var error=credentialProblem(jobs[key].credential);if(error){setCredentialTouched(true);A.message.warning(error);return;}}setJobs(function(old){var n=Object.assign({},old);n[key]=Object.assign({},old[key],{status:old[key].status==='paused'?'running':'paused'});return n;});}
    function issues(job){if(!job||job.status!=='done')return [];return job.files.filter(function(f){return job.op==='扫描'?(f.hits>0||f.state==='处理失败'||f.local):!!f.reason;});}
    function resultReport(job){var rows=issues(job);var html='<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>异常报告</title><style>body{font:14px/1.8 Arial,sans-serif;padding:32px}table{width:100%;border-collapse:collapse}td,th{padding:12px;text-align:left;border-bottom:1px solid #ddd}</style><h1>异常报告</h1><p>'+escapeHTML(job.id)+'</p><table><tr><th>异常文件</th><th>问题</th></tr>'+rows.map(function(f){return '<tr><td>'+escapeHTML((f.path?f.path+'/':'')+f.name)+'</td><td>'+escapeHTML(f.reason||'命中敏感词')+'</td></tr>';}).join('')+'</table></html>';download(job.id+'-异常报告.html',html,'text/html;charset=utf-8');}
    function book(job,format){var entries=job.mappings||(registry[job.id]?registry[job.id].mappings:null)||mockMappings(job);if(format==='html'){download(job.id+'-密码本.html','<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>密码本</title><style>body{font:14px/1.8 Arial,sans-serif;padding:32px}td,th{padding:12px;text-align:left;border-bottom:1px solid #ddd}table{width:100%;border-collapse:collapse}</style><h1>密码本</h1><p>'+escapeHTML(job.id)+' · 演示</p><table><tr><th>原词</th><th>平替词</th></tr>'+entries.map(function(m){return '<tr><td>'+escapeHTML(m.original)+'</td><td>'+escapeHTML(m.replacement)+'</td></tr>';}).join('')+'</table></html>','text/html;charset=utf-8');}else{download(job.id+'-密码本.json',JSON.stringify({demo:true,task:job.id,version:job.version,mappings:entries},null,2),'application/json');}}
    function jobPercent(j){if(j.status==='done')return 100;return Math.round(j.phase==='parse'?j.parseProgress*0.2:20+j.completed/Math.max(1,j.total)*80);}
    function inputRow(key){return h('div',{className:'io-row'},h('label',{className:'field-label'},'输入'),h(Input,{value:inputs[key],readOnly:true,placeholder:'选择文件或文件夹','aria-label':opNames[key]+'输入'}),h(Button,{icon:icon('folder'),onClick:function(){chooseFolder(key);},disabled:jobs[key]&&['running','paused'].indexOf(jobs[key].status)>-1},'选择文件夹'),key!=='scan'?h(Button,{icon:icon('file'),onClick:function(){chooseFiles(key);},disabled:jobs[key]&&['running','paused'].indexOf(jobs[key].status)>-1},'选择文件'):h(Button,{type:'text',onClick:function(){changeInput('scan','项目交付资料');setSources(function(old){return Object.assign({},old,{scan:sampleFiles});});},disabled:jobs.scan&&['running','paused'].indexOf(jobs.scan.status)>-1},'示例'))}
    function execution(key){var job=jobs[key],busy=job&&['running','paused'].indexOf(job.status)>-1;return h('div',{className:'execution-page'},h('div',{className:'execution-heading'},h('h1',null,opNames[key]),job?h(Tag,{color:job.status==='running'?'processing':job.status==='paused'?'warning':'success'},job.status==='running'?'执行中':job.status==='paused'?'已暂停':'已完成'):null),h('section',{className:'panel execution-config'},inputRow(key),key!=='scan'?h('div',{className:'io-row'},h('label',{className:'field-label'},'输出'),h(Input,{value:outputs[key],onChange:function(e){changeOutput(key,e.target.value);},disabled:busy,'aria-label':opNames[key]+'输出目录'})):null,
      h('div',{className:'execution-options'},key==='scan'?null:key==='redact'?h(Radio.Group,{value:mode,onChange:function(e){setMode(e.target.value);},disabled:busy},h(Radio,{value:'A'},'标记平替 A'),h(Radio,{value:'B'},'自然词平替 B')):credentialControl(busy),h('div',{className:'execution-buttons'},busy?h(Button,{onClick:function(){pause(key);}},job.status==='paused'?'继续'+opNames[key]:'暂停'+opNames[key]):h(Button,{type:'primary',onClick:function(){launch(key,sources[key]||sampleFiles);},disabled:!inputs[key]||(key!=='scan'&&!outputs[key])||(key==='restore'&&!!credentialProblem())},'开始'+opNames[key])))),
      h('section',{className:'panel execution-progress'},h('div',{className:'section-heading'},h('h2',null,'执行进度'),job?h('span',{className:'caption'},job.id):null),h(Steps,{size:'small',responsive:false,current:!job?0:job.phase==='parse'?0:job.status==='done'?2:1,items:[{title:'解析总目标'},{title:key==='scan'?'并行扫描':opNames[key]+'处理'},{title:'完成'}]}),h(Progress,{percent:job?jobPercent(job):0,status:job&&job.status==='running'?'active':'normal',strokeColor:'#186c64'}),h('div',{className:'progress-facts'},h('span',null,'目标总数 ',h('strong',null,job&&job.phase!=='parse'?job.total:'—')),h('span',null,'已完成 ',h('strong',null,job?job.completed:0)),h('span',null,'并行 ',h('strong',null,job?job.workers:4)),job&&job.output?h('span',{className:'progress-output'},'输出：'+job.output):null)),
      h('section',{className:'panel execution-exceptions'},h('div',{className:'section-heading'},h('h2',null,'异常报告'),job&&job.status==='done'?h(Space,null,key==='redact'?h(Button,{size:'small',onClick:function(){book(job,'html');}},'密码本 HTML'):null,key==='redact'?h(Button,{size:'small',onClick:function(){book(job,'json');}},'密码本 JSON'):null,h(Button,{size:'small',onClick:function(){resultReport(job);}},'导出报告')):null),job&&job.status==='done'?h(Table,{size:'middle',pagination:false,dataSource:issues(job),columns:[{title:'异常文件',width:'30%',render:function(_,f){return fileCell(f,setDetail);}},{title:'异常项',render:function(_,f){return f.reason||baseWords.filter(function(w){return (f.preview||'').indexOf(w.word)>-1;}).map(function(w){return w.word;}).join('、');}},{title:'命中',dataIndex:'hits',width:90}],locale:{emptyText:'无异常'}}):h('div',{className:'blank-report'},job?'等待任务完成':'—')));}
    function allTasks(){var tasks=Object.keys(jobs).map(function(k){var j=jobs[k];return Object.assign({},j,{key:k,live:true,status:j.status==='running'?'执行中':j.status==='paused'?'已暂停':'已完成'});}).concat(historyTasks.filter(function(t){return !Object.keys(jobs).some(function(k){return jobs[k].id===t.id;});}));Object.keys(registry).forEach(function(id){if(!tasks.some(function(t){return t.id===id;}))tasks.push(registry[id].task);});return tasks;}

    function taskPage(){return h('div',{className:'fill-page'},h('div',{className:'execution-heading transfer-heading'},h('h1',null,'任务记录'),h(Space,{wrap:true},h(Button,{loading:transferBusy,onClick:function(){openPackage('tasks');}},'导入脱敏任务'),h(Button,{loading:transferBusy,disabled:!selectedTasks.length,onClick:function(){exportPackages(selectedTasks);}},'导出所选密码包'),h(Button,{loading:transferBusy,onClick:function(){exportPackages(Object.keys(registry));}},'导出全部密码包'))),h('section',{className:'panel fill-panel'},h(Table,{rowKey:'id',dataSource:allTasks(),pagination:false,rowSelection:{selectedRowKeys:selectedTasks,onChange:function(ids){setSelectedTasks(ids.filter(function(id){return !!registry[id];}));},getCheckboxProps:function(task){return {disabled:task.op!=='脱敏'||!registry[task.id],'aria-label':'选择任务 '+task.id};}},columns:[{title:'任务号',dataIndex:'id'},{title:'类型',dataIndex:'op',width:90},{title:'状态',dataIndex:'status',width:100,render:statusTag},{title:'进度',width:150,render:function(_,j){return j.live?h(Progress,{percent:jobPercent(j),size:'small',strokeColor:'#186c64'}):'100%';}},{title:'操作',width:175,render:function(_,j){return h(Space,null,h(Button,{type:'link',onClick:function(){if(j.live){setPage(j.key);return;}var key=Object.keys(opNames).filter(function(k){return opNames[k]===j.op;})[0];if(jobs[key]&&['running','paused'].indexOf(jobs[key].status)>-1){A.message.info('该页面有正在执行的任务');setPage(key);return;}setJobs(function(old){var next=Object.assign({},old);next[key]=Object.assign({},j,{total:j.files.length,completed:j.files.length,status:'done',phase:'scan',workers:4});return next;});changeInput(key,j.input||j.name);if(key!=='scan')changeOutput(key,j.outputRoot||'/data/tasks');setPage(key);}},'查看'),j.op==='脱敏'&&registry[j.id]?h(Button,{type:'link',onClick:function(){exportPackages([j.id]);}},'导出密码包'):null);}}]})));}

    function libraryPage(){var data=libraryTab==='sensitive'?words:pool;return h('div',{className:'fill-page'},h('div',{className:'execution-heading'},h('h1',null,'词库管理'),h(Space,{wrap:true},h('span',null,'V'+version),h(Button,{loading:transferBusy,onClick:function(){libraryInput.current.value='';libraryInput.current.click();}},'导入更新'),h(Button,{onClick:exportLibrary},'导出词库'),h(Button,{type:'primary',onClick:function(){setAdding(true);}},'新增词条'))),h('section',{className:'panel fill-panel'},h(Tabs,{activeKey:libraryTab,onChange:setLibraryTab,items:[{key:'sensitive',label:'敏感词库'},{key:'pool',label:'平替词库'}]}),h(Table,{dataSource:data,rowKey:'id',pagination:false,columns:[{title:'词条',dataIndex:'word'},{title:'ID',dataIndex:'id'},{title:'语义类型',dataIndex:'role'},{title:'状态',dataIndex:'status',render:statusTag},{title:'操作',width:90,render:function(_,x){return h(Button,{type:'link',disabled:x.status==='弃用',onClick:function(){var setter=libraryTab==='sensitive'?setWords:setPool;setter(function(old){return old.map(function(w){return w.id===x.id?Object.assign({},w,{status:'弃用'}):w;});});setVersion(function(n){return n+1;});}},'弃用');}}]})));}
    var navs=[{key:'scan',label:'扫描',icon:'scan'},{key:'redact',label:'脱敏',icon:'swap'},{key:'restore',label:'还原',icon:'restore'},{key:'tasks',label:'任务记录',icon:'tasks'},{key:'library',label:'词库管理',icon:'library'}];
    return h(A.ConfigProvider,{locale:A.locales.zh_CN},h('div',{className:'app'},h('aside',{className:'sidebar'},h(Brand),h('nav',{className:'nav-list','aria-label':'主导航'},navs.map(function(n){var running=jobs[n.key]&&jobs[n.key].status==='running';return h('button',{key:n.key,className:'nav-item '+(page===n.key?'active':''),title:n.label,onClick:function(){setPage(n.key);}},icon(n.icon),h('span',{className:'nav-label'},n.label),running?h('span',{className:'nav-running','aria-label':'运行中'}):null);}))),h('div',{className:'shell'},h('main',{className:'main'},opNames[page]?execution(page):page==='tasks'?taskPage():libraryPage())),h('input',{ref:folderInput,type:'file',webkitdirectory:'',directory:'',multiple:true,style:{display:'none'},'aria-label':'系统文件夹选择',onChange:function(e){picked(e,true);}}),h('input',{ref:libraryInput,type:'file',accept:'.json',style:{display:'none'},'aria-label':'词库导入文件',onChange:importLibrary}),h('input',{ref:packageInput,type:'file',accept:'.swapy,.zip',style:{display:'none'},'aria-label':'脱敏密码包导入文件',onChange:importPackages}),h('input',{ref:fileInput,type:'file',multiple:true,style:{display:'none'},'aria-label':'系统文件选择',onChange:function(e){picked(e,false);}}),h(Drawer,{title:detail?detail.name:'详情',width:'100%',open:!!detail,onClose:function(){setDetail(null);}},detail?h('div',{className:'full-detail'},h('div',{className:'detail-value'},(detail.path?detail.path+'/':'')+detail.name),h('div',{className:'text-preview'},detail.preview||detail.reason||'—')):null),h(Modal,{title:libraryTab==='sensitive'?'新增敏感词':'新增平替词',open:adding,width:'100%',style:{top:0},className:'full-modal',onCancel:function(){setAdding(false);},onOk:addWord,okText:'新增',cancelText:'取消'},h(Form,{form:wordForm,layout:'vertical'},h(Form.Item,{name:'word',label:'词条',rules:[{required:true,message:'请输入词条'}]},h(Input)),h(Form.Item,{name:'role',label:'语义类型',initialValue:'机构名'},h(Select,{options:['机构名','人名','地名','项目代号','产品名','字段名'].map(function(x){return {label:x,value:x};})}))))));
  }
  ReactDOM.render(h(App),document.getElementById('root'));
})();









