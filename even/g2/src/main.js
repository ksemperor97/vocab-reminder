import {waitForEvenAppBridge,TextContainerProperty,TextContainerUpgrade,CreateStartUpPageContainer} from '@evenrealities/even_hub_sdk';
import {actionForEvent} from './session.js';
import {liveText,receive,responses} from './live.js';
import './style.css';
const key='vocab-g2-live-v1';
let saved={},s={online:false,prompt:null,pending:null,revealed:false,selection:0,message:''};
let messageAt=0;
let bridge,ready=false,busy=false,polling=false,lastInput=0,lastRendered='';
const screen=document.querySelector('#screen'),status=document.querySelector('#connection'),feedback=document.querySelector('#feedback');
const origin=__G2_SERVER_ORIGIN__;
async function persist() {
  const raw=JSON.stringify({...saved,pending:s.pending});
  if(bridge) { if(await bridge.setLocalStorage(key,raw)!==true) throw Error('연결 정보 저장 실패'); }
  else localStorage.setItem(key,raw);
}
async function render(){
  const content=liveText(s);screen.textContent=content;
  if(ready && lastRendered!==content) {
    if(await bridge.textContainerUpgrade(new TextContainerUpgrade({containerID:1,containerName:'study',content}))===false) throw Error('안경 화면 갱신 실패');
    lastRendered=content;
  }
}
async function request(path,body,auth=true){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),8000);
  try {
    const r=await fetch(origin+path,{method:body?'POST':'GET',headers:{...(body?{'Content-Type':'application/json'}:{}),...(auth?{Authorization:`Bearer ${saved.token}`}:{})},body:body?JSON.stringify(body):undefined,signal:controller.signal});
    const data=await r.json();
    if(!r.ok) throw Object.assign(Error(data.error?.message??'연결 실패'),{status:r.status});
    return data;
  }finally{clearTimeout(timer);}
}
async function poll(){
  if(polling||busy||!ready||!saved.token) return; polling=true;
  try {
    if(s.pending) {
      try { await request('/g2/answer',{sessionId:saved.id,...s.pending}); }
      catch(e) {
        if(e.status===409) { s.pending=null;s.message='문제가 변경되어 응답을 반영하지 않았습니다.';messageAt=Date.now();await persist(); }
        else throw e;
      }
    }
    const remote=await request('/g2/state?id='+encodeURIComponent(saved.id));
    const hadPending=!!s.pending;
    s=receive(s,remote);
    if(hadPending && !s.pending) messageAt=Date.now();
    if(s.message && Date.now()-messageAt>5000) s.message="";
    await persist();
    status.textContent=remote.online?'Android 연결됨 · 실제 복습 기록에 반영됩니다.':'Android 연결 대기 중';
    feedback.textContent='';await render();
  }catch(e){s.online=false;status.textContent=e.status===401?'연결이 만료되었습니다. 새 코드로 연결하세요.':'연결 재시도 중';feedback.textContent=e.message;await render().catch(()=>{});}
  finally{polling=false;}
}
async function input(action){
  if(busy||polling||!ready||Date.now()-lastInput<350) return;lastInput=Date.now();busy=true;
  try{
    if(action==='back') {
      if(s.revealed) s.revealed=false;
      else await bridge.shutDownPageContainer(1);
    } else if(s.pending) return;
    else if(s.message && action==='click') s.message='';
    else if(s.online && s.prompt) {
      if(s.revealed && ['up','down'].includes(action)) s.selection=(s.selection+(action==='up'?2:1))%3;
      else if(action==='click') {
        if(!s.revealed) s.revealed=true;
        else { s.pending={id:crypto.randomUUID(),promptId:s.prompt.id,response:responses[s.selection]};try { await persist(); } catch(e) { s.pending=null;throw e; } }
      }
    }
    await render();
  }catch(e){feedback.textContent=e.message;}
  finally{busy=false;}
  void poll();
}
for(const a of ['up','click','down','back']) document.getElementById(a).onclick=()=>input(a);
document.querySelector('#pair').onclick=async()=>{
  if(busy||polling||!ready||s.pending) return;busy=true;
  try{
    const data=await request('/g2/pair',{code:document.querySelector('#code').value.trim()},false);
    saved=data;s.message='';await persist();document.querySelector('#code').value='';feedback.textContent='연결되었습니다.';
  }catch(e){feedback.textContent=e.message;}
  finally{busy=false;}void poll();
};
waitForEvenAppBridge().then(async b=>{
  bridge=b;
  try {
    const raw=await b.getLocalStorage(key);saved=raw?JSON.parse(raw):{};s.pending=saved.pending??null;
    const result=await b.createStartUpPageContainer(new CreateStartUpPageContainer({containerTotalNum:1,textObject:[new TextContainerProperty({xPosition:0,yPosition:0,width:576,height:288,paddingLength:8,borderWidth:0,containerID:1,containerName:'study',isEventCapture:1,content:liveText(s)})]}));
    if(result!==0) throw Error('G2 화면 생성 실패: '+result);
    ready=true;status.textContent=saved.token?'연결 복구 중…':'Android 앱에서 연결 코드를 받아 입력하세요.';
    b.onEvenHubEvent(e=>{const action=actionForEvent(e);if(action) void input(action);});
    await render();void poll();setInterval(()=>void poll(),3000);
  }catch(e){feedback.textContent=e.message;}
}).catch(e=>{feedback.textContent=e.message;});
document.addEventListener('visibilitychange',()=>{if(!document.hidden) void poll();});
void render();
