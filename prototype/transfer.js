(function(root){
  'use strict';
  function fail(message){throw new Error(message);}
  function version(value){var n=Number(value);if(!Number.isSafeInteger(n)||n<1)fail('词库版本无效');return n;}
  function ready(task){return task&&task.op==='脱敏'&&['done','已完成','部分完成'].indexOf(task.status)>-1;}
  function id(value){if(typeof value!=='string'||!/^[A-Za-z0-9_-]{1,120}$/.test(value)||['__proto__','constructor','prototype'].indexOf(value)>-1)fail('任务 ID 无效');return value;}
  function uniqueWordId(prefix,existing,storage,crypto){
    if(!crypto||!crypto.getRandomValues)fail('当前环境无法安全生成词条 ID');
    var used=Object.create(null);existing.forEach(function(x){used[x.id]=true;});
    var ledger;try{ledger=JSON.parse(storage.getItem('swapy.issued.wordids')||'[]');if(!Array.isArray(ledger))fail('ID 登记异常');}catch(e){fail('无法读取 ID 登记');}
    ledger.forEach(function(x){used[x]=true;});
    for(var attempt=0;attempt<100;attempt++){
      var bytes=new Uint8Array(16);crypto.getRandomValues(bytes);bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;
      var hex=Array.prototype.map.call(bytes,function(x){return x.toString(16).padStart(2,'0');}).join('');
      var value=prefix+'-'+hex.slice(0,8)+'-'+hex.slice(8,12)+'-'+hex.slice(12,16)+'-'+hex.slice(16,20)+'-'+hex.slice(20);
      if(used[value])continue;
      try{storage.setItem('swapy.issued.wordids',JSON.stringify(ledger.concat([value])));}catch(e){fail('无法登记新 ID，未创建词条');}
      return value;
    }
    fail('ID 冲突，未创建词条');
  }
  function entries(list){if(!Array.isArray(list))fail('词库内容无效');return list.map(function(x){if(!x||typeof x.id!=='string'||!x.id||typeof x.word!=='string'||!x.word.trim()||typeof x.role!=='string'||!x.role||['启用','弃用'].indexOf(x.status)===-1)fail('词条字段无效');return {id:x.id,word:x.word,role:x.role,form:x.form||'中文词',source:x.source||'用户手填',status:x.status};});}
  function library(input){if(!input||input.format!=='swapy-library'||input.formatVersion!==1)fail('不是支持的词库文件');var data={format:'swapy-library',formatVersion:1,version:version(input.version),sensitive:entries(input.sensitive),pool:entries(input.pool)};var seen=Object.create(null);data.sensitive.concat(data.pool).forEach(function(x){if(seen[x.id])fail('词库含重复 ID：'+x.id);seen[x.id]=true;});return data;}
  function mergeLibrary(current,incoming){
    var next=library(incoming),old=library(current);if(next.version<old.version)fail('导入词库版本低于本地，未导入');
    var known=Object.create(null);['sensitive','pool'].forEach(function(kind){old[kind].forEach(function(x){known[x.id]={kind:kind,item:x};});});
    ['sensitive','pool'].forEach(function(kind){next[kind].forEach(function(x){var prior=known[x.id];if(prior&&(prior.kind!==kind||prior.item.word!==x.word||prior.item.role!==x.role||prior.item.form!==x.form))fail('ID 对应内容冲突：'+x.id);});});
    var result={format:'swapy-library',formatVersion:1,version:Math.max(old.version,next.version)};
    ['sensitive','pool'].forEach(function(kind){var index=Object.create(null);result[kind]=old[kind].map(function(x,i){index[x.id]=i;return Object.assign({},x);});next[kind].forEach(function(x){if(index[x.id]!==undefined){var before=result[kind][index[x.id]];result[kind][index[x.id]]=Object.assign({},x,{status:before.status==='弃用'?'弃用':x.status});}else{index[x.id]=result[kind].length;result[kind].push(x);}});});return result;
  }
  function record(task,mappings){
    if(!ready(task))fail('仅支持已完成的脱敏任务导出');
    var files=(task.files||[]).map(function(f){return {key:String(f.key||f.name),name:String(f.name||''),path:String(f.path||''),size:String(f.size||''),ext:String(f.ext||''),hits:Number(f.hits)||0,state:String(f.state||''),reason:f.reason?String(f.reason):undefined};});
    return validateRecord({format:'swapy-prototype-task',formatVersion:1,kind:'redaction-task',demo:true,task:{id:id(task.id),op:'脱敏',name:String(task.name||task.id),input:String(task.input||''),output:String(task.output||''),outputRoot:String(task.outputRoot||''),mode:task.mode||'A',version:version(task.version),status:task.status==='部分完成'?'部分完成':'已完成',files:files,startedAt:Number(task.startedAt)||0,finishedAt:Number(task.finishedAt)||0},mappings:mappings||[]});
  }
  function validateRecord(input){
    if(!input||input.format!=='swapy-prototype-task'||input.formatVersion!==1||input.kind!=='redaction-task'||!ready(input.task))fail('仅支持脱敏任务密码包');
    id(input.task.id);version(input.task.version);if(['A','B'].indexOf(input.task.mode)===-1||!Array.isArray(input.task.files)||!Array.isArray(input.mappings))fail('密码包内容无效');
    input.task.files.forEach(function(f){if(!f||typeof f.name!=='string'||!f.name||typeof f.path!=='string'||typeof f.state!=='string')fail('密码包文件记录无效');});
    input.mappings.forEach(function(m){if(!m||typeof m.sourceId!=='string'||typeof m.original!=='string'||typeof m.replacement!=='string')fail('密码包映射无效');});
    return JSON.parse(JSON.stringify(input));
  }
  function signature(item){function sorted(value){if(Array.isArray(value))return value.map(sorted);if(value&&typeof value==='object'){var result=Object.create(null);Object.keys(value).sort().forEach(function(key){result[key]=sorted(value[key]);});return result;}return value;}return JSON.stringify(sorted(item));}
  function mergeRecords(current,incoming,knownTasks){var next=Object.assign({},current),seen=Object.create(null);incoming.forEach(function(raw){var item=validateRecord(raw),key=item.task.id;var known=(knownTasks||[]).filter(function(t){return t.id===key;});if(known.some(function(t){return t.op!=='脱敏';})||(known.length&&!Object.prototype.hasOwnProperty.call(current,key)))fail('本地任务 ID 冲突：'+key);if(seen[key]&&signature(seen[key])!==signature(item))fail('任务 ID 内容冲突：'+key);if(next[key]&&signature(next[key])!==signature(item))fail('本地任务 ID 内容冲突：'+key);seen[key]=item;next[key]=item;});return next;}
  function pack(item){var zip=new root.JSZip();zip.file('record.json',JSON.stringify(validateRecord(item)));return zip.generateAsync({type:'uint8array',compression:'DEFLATE'});}
  function exportMany(items){if(!items.length)return Promise.reject(new Error('没有可导出的脱敏任务'));var checked=items.map(validateRecord);return Promise.all(checked.map(pack)).then(function(bytes){var zip=new root.JSZip();checked.forEach(function(item,i){zip.file(item.task.id+'.swapy',bytes[i]);});return zip.generateAsync({type:'uint8array',compression:'DEFLATE'});});}
  function unpack(bytes,depth){if(depth>1)return Promise.reject(new Error('密码包嵌套过深'));return root.JSZip.loadAsync(bytes,{checkCRC32:true}).then(function(zip){var recordFile=zip.file('record.json');if(recordFile)return recordFile.async('string').then(function(text){if(text.length>5000000)fail('密码包记录过大');return [validateRecord(JSON.parse(text))];});var names=Object.keys(zip.files).filter(function(name){return !zip.files[name].dir;});if(!names.length||names.length>1000||names.some(function(name){return !/\.swapy$/i.test(name);}))fail('不是脱敏任务密码包合集');return Promise.all(names.map(function(name){return zip.file(name).async('uint8array').then(function(data){return unpack(data,depth+1);});})).then(function(groups){return [].concat.apply([],groups);});});}
  root.SwapyTransfer={ready:ready,uniqueWordId:uniqueWordId,library:library,mergeLibrary:mergeLibrary,record:record,validateRecord:validateRecord,mergeRecords:mergeRecords,pack:pack,exportMany:exportMany,unpack:function(bytes){return unpack(bytes,0);}};
})(window);
