const $ = s => document.querySelector(s);
let session = localStorage.getItem('mindbridge-session');
let busy = false;
let noticeTimer;
const labels = {NORMAL:'No rule matched', ANXIETY:'Stress / anxiety signal', LOW_MOOD:'Low mood signal', HIGH_RISK:'Safety concern'};
async function api(path, options = {}) {
  const response = await fetch('/api' + path, {headers:{'Content-Type':'application/json'}, ...options});
  if (!response.ok) throw new Error(response.status === 404 ? 'Session not found. Start a new conversation.' : `Request failed (${response.status}). Please try again.`);
  return response.json();
}
function notice(message) {
  $('#notice').textContent = message; $('#notice').hidden = false;
  clearTimeout(noticeTimer); noticeTimer = setTimeout(() => $('#notice').hidden = true, 6500);
}
function bubble(role, text) {
  $('.welcome')?.remove();
  const node = document.createElement('div'); node.className = 'message ' + role;
  const label = document.createElement('span'); label.className = 'message-label'; label.textContent = role === 'user' ? 'YOU' : 'MINDBRIDGE';
  const content = document.createElement('span'); content.textContent = text;
  node.append(label, content); $('#messages').append(node); scrollChat();
  return {node, content};
}
function scrollChat() { $('#messages').scrollTop = $('#messages').scrollHeight; }
async function ensureSession() {
  if (!session) { session = (await api('/conversations', {method:'POST'})).id; localStorage.setItem('mindbridge-session',session); }
  return session;
}
function showInsight(data) {
  $('#insight-placeholder').hidden = true; $('#insight').hidden = false;
  $('#risk-badge').textContent = labels[data.risk.level];
  $('#risk-badge').className = 'risk-badge' + (data.risk.level === 'HIGH_RISK' ? ' high' : '');
  $('#risk-reason').textContent = data.risk.reason;
  $('#agent-name').textContent = data.risk.agent;
  $('#model-name').textContent = data.provider;
  $('#sources').replaceChildren();
  for (const source of data.sources) {
    const node = document.createElement('div'); node.className = 'source'; node.textContent = source.title;
    const meta = document.createElement('small'); meta.textContent = `Retrieved reference · BM25 ${source.score}`;
    node.append(meta); $('#sources').append(node);
  }
  if(data.alertId) notice('A local safety alert was created. No external notification was sent.');
}
async function send(text) {
  if(busy || !text.trim()) return;
  busy = true; $('#send').disabled = true; $('#new-session').disabled = true;
  let reply, complete = false;
  try {
    await ensureSession();
    bubble('user', text); $('#message').value = '';
    reply = bubble('assistant', 'Listening…');
    const response = await fetch(`/api/conversations/${session}/messages`, {method:'POST', headers:{'Content-Type':'application/json','Accept':'text/event-stream'},body:JSON.stringify({message:text})});
    if(!response.ok) throw new Error(`Message could not be sent (${response.status}). Please try again.`);
    const reader = response.body.getReader(); const decoder = new TextDecoder(); let pending = '', content = '';
    function processFrame(frame) {
      const lines = frame.split('\n'); const type = lines.find(l => l.startsWith('event:'))?.slice(6).trim();
      const raw = lines.filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trimStart()).join('\n');
      if(!raw) return;
      const data = JSON.parse(raw);
      if(type === 'meta') showInsight(data);
      if(type === 'token') { content += data.text; reply.content.textContent = content; scrollChat(); }
      if(type === 'done') complete = true;
      if(type === 'error') throw new Error(data.message);
    }
    try {
      while(true) {
        const {value,done} = await reader.read();
        pending += decoder.decode(value || new Uint8Array(), {stream:!done}).replace(/\r\n/g,'\n');
        let split;
        while((split = pending.indexOf('\n\n')) >= 0) {processFrame(pending.slice(0,split));pending=pending.slice(split+2);}
        if(done) break;
      }
      if(!complete) throw new Error('The connection ended before the reply was saved. Please try again.');
    } finally { await reader.cancel().catch(()=>{}); }
  } catch(e) {
    if(reply) {reply.node.classList.add('failed'); reply.content.textContent += '\n\n' + e.message;}
    notice(e.message);
  } finally {
    busy=false; $('#send').disabled=false; $('#new-session').disabled=false; $('#message').focus(); refreshStatus().catch(()=>{});
  }
}
$('#chat-form').addEventListener('submit', e=>{e.preventDefault();send($('#message').value.trim());});
$('#message').addEventListener('keydown', e=>{if(e.key==='Enter' && !e.shiftKey && !e.isComposing){e.preventDefault();$('#chat-form').requestSubmit();}});
document.querySelectorAll('[data-prompt]').forEach(b=>b.addEventListener('click',()=>send(b.dataset.prompt)));
$('#new-session').addEventListener('click',()=>{if(!busy){localStorage.removeItem('mindbridge-session');location.reload();}});
$('#download-report').addEventListener('click',async()=>{
  try {
    if(!session) return notice('Start a conversation to create a report.');
    if(busy) return notice('Wait for the current reply to finish before exporting.');
    const report = await api(`/conversations/${session}/report`);
    const url = URL.createObjectURL(new Blob([JSON.stringify(report,null,2)],{type:'application/json'}));
    const link = document.createElement('a');link.href=url;link.download=`mindbridge-${session.slice(0,8)}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  }catch(e){notice(e.message);}
});
async function refreshStatus() {
  const data = await api('/status');
  $('#provider-label').textContent = data.provider === 'demo' ? 'Demo mode · no AI connection' : data.provider + ' · configured';
  $('.demo-pill').textContent = 'MVP · ' + data.provider.toUpperCase();
  $('#alert-count').textContent = data.stats.openAlerts;
  $('#stats').replaceChildren();
  for(const [key,label] of [['conversations','Conversations'],['messages','Check-in messages'],['openAlerts','Open local alerts']]) {
    const node=document.createElement('div');node.className='stat';const n=document.createElement('strong');n.textContent=data.stats[key];const l=document.createElement('span');l.textContent=label;node.append(n,l);$('#stats').append(node);
  }
}
async function loadKnowledge() {
  const docs=await api('/knowledge');$('#knowledge-grid').replaceChildren();
  docs.forEach((doc,index)=>{
    const node=document.createElement('article');node.className='resource';
    const num=document.createElement('span');num.className='resource-number';num.textContent='0'+(index+1);
    const title=document.createElement('h2');title.textContent=doc.title;
    const body=document.createElement('p');body.textContent=doc.content;
    const source=document.createElement('small');source.textContent=doc.source;
    node.append(num,title,body,source);$('#knowledge-grid').append(node);
  });
}
async function loadAlerts() {
  await refreshStatus();const alerts=await api('/alerts');$('#alerts-list').replaceChildren();
  if(!alerts.length){const empty=document.createElement('div');empty.className='empty-state';empty.textContent='No local alerts to review.';$('#alerts-list').append(empty);return;}
  for(const alert of alerts){
    const row=document.createElement('article');row.className='alert-row';
    const detail=document.createElement('div');const state=document.createElement('small');state.textContent=alert.status==='OPEN'?'NEEDS REVIEW':'ACKNOWLEDGED';
    const title=document.createElement('h3');title.textContent=alert.reason;
    const time=document.createElement('p');time.textContent=`Session ${alert.conversation_id.slice(0,8)} · ${new Date(alert.created_at).toLocaleString()} · Local record only`;
    detail.append(state,title,time);row.append(detail);
    if(alert.status==='OPEN'){const button=document.createElement('button');button.className='subtle-button';button.textContent='Acknowledge';button.addEventListener('click',async()=>{button.disabled=true;try{await api(`/alerts/${alert.id}/acknowledge`,{method:'POST'});await loadAlerts();}catch(e){notice(e.message);button.disabled=false;}});row.append(button);}
    $('#alerts-list').append(row);
  }
}
document.querySelectorAll('[data-view]').forEach(button=>button.addEventListener('click',async()=>{
  document.querySelectorAll('[data-view]').forEach(b=>b.classList.toggle('selected',b===button));
  document.querySelectorAll('.view').forEach(v=>v.hidden=v.id!==button.dataset.view+'-view');
  $('#page-label').textContent={chat:'DAILY CHECK-IN',knowledge:'RESOURCE LIBRARY',alerts:'SUPPORT DESK'}[button.dataset.view];
  try{if(button.dataset.view==='knowledge') await loadKnowledge();if(button.dataset.view==='alerts') await loadAlerts();}catch(e){notice(e.message);}
}));
$('#refresh-alerts').addEventListener('click',()=>loadAlerts().catch(e=>notice(e.message)));
(async()=>{
  try{
    await refreshStatus();
    if(session){
      try{const history=await api(`/conversations/${session}/messages`);history.forEach(m=>bubble(m.role,m.content));}
      catch(e){if(e.message.startsWith('Session not found')){session=null;localStorage.removeItem('mindbridge-session');}else throw e;}
    }
  }catch(e){notice('Cannot reach MindBridge. Start the backend and refresh.');}
})();
