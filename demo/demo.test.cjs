const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
function client(role,storage=new Map()){
  storage.set('mindbridge-demo-role',role);
  const context=vm.createContext({window:{},sessionStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},crypto:require('node:crypto').webcrypto,Response,ReadableStream,TextEncoder,Blob,setTimeout:fn=>setTimeout(fn,0),clearTimeout});
  for(const file of ['workbook.js','demo-api.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,file),'utf8'),context);
  return {storage,window:context.window,fetch:context.window.demoFetch};
}
async function create(c){return (await (await c.fetch('/api/conversations',{method:'POST'})).json()).id;}
async function send(c,id,message){return (await c.fetch(`/api/conversations/${id}/messages`,{method:'POST',body:JSON.stringify({message})})).text();}
test('ordinary chat streams without creating a screening report',async()=>{
  const c=client('student'),id=await create(c);const stream=await send(c,id,'Hello, I had a good day');assert.match(stream,/event:done/);assert.match(stream,/"intent":"CHAT"/);
  const admin=client('admin',c.storage);assert.deepEqual(await (await admin.fetch('/api/admin/reports')).json(),[]);
});
test('consultation creates a report with references',async()=>{
  const c=client('student'),id=await create(c);const stream=await send(c,id,'I feel anxious about exams');assert.match(stream,/"intent":"CONSULT"/);assert.match(stream,/One small step/);
  const admin=client('admin',c.storage);const reports=await (await admin.fetch('/api/admin/reports')).json();assert.equal(reports.length,1);assert.equal(reports[0].response_status,'COMPLETE');assert.equal(reports[0].tool_status,undefined);
});
test('ledger failure blocks notification and retry is deduplicated',async()=>{
  const c=client('student'),id=await create(c);c.window.demoFailNext();await send(c,id,'Synthetic example: I want to hurt myself');
  const admin=client('admin',c.storage);let reports=await (await admin.fetch('/api/admin/reports')).json();const r=reports[0];assert.equal(r.tool_status,'FAILED');assert.equal(r.notification_status,'PENDING');
  assert.equal((await admin.fetch(`/api/admin/reports/${r.id}/retry`,{method:'POST'})).status,200);
  const detail=await (await admin.fetch('/api/admin/reports/'+r.id)).json();assert.equal(detail.report.tool_status,'COMPLETE');assert.deepEqual(detail.events.map(e=>e.outcome),['FAILED','SUCCEEDED','RECORDED']);
  assert.equal((await admin.fetch(`/api/admin/reports/${r.id}/retry`,{method:'POST'})).status,409);
  const xlsx=await admin.fetch('/api/admin/excel');assert.equal(xlsx.status,200);const bytes=Buffer.from(await xlsx.arrayBuffer());assert.equal(bytes.readUInt32LE(0),0x04034b50);fs.writeFileSync('/tmp/mindbridge-browser-demo.xlsx',bytes);
});
test('simulated students have separate session lists and inputs are rendered as text',async()=>{
  const c=client('student'),id=await create(c);await send(c,id,'<script>alert(1)</script>');
  const other=client('student2',c.storage);assert.deepEqual(await (await other.fetch('/api/conversations')).json(),[]);assert.equal((await other.fetch(`/api/conversations/${id}/messages`)).status,404);assert.equal((await other.fetch('/api/admin/reports')).status,403);
});
