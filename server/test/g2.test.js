import test from 'node:test';
import assert from 'node:assert/strict';
import {openDatabase} from '../src/db.js';
import {G2Relay} from '../src/g2.js';
function setup(t){const db=openDatabase(':memory:');t.after(()=>db.close());let now=100000;const relay=new G2Relay(db,()=>now);const session=relay.create();const pair=relay.pair(session.code);return {relay,session,pair,advance:()=>now+=21000};}
const prompt={id:'p1',cardId:'c1',revision:'1:time',word:'test',meaning:'시험'};
const answer={id:'answer1',promptId:'p1',response:'remembered'};
test('pairing code is one-use and token cannot access a different session',t=>{const {relay,session,pair}=setup(t);assert.throws(()=>relay.pair(session.code));const other=relay.create();assert.throws(()=>relay.state(other.id,pair.token));assert.equal(relay.state(session.id,pair.token).online,false);});
test('durable answer retry and duplicate host acknowledgement preserve one action',t=>{const {relay,session,pair}=setup(t);relay.host(session.id,{prompt});relay.answer(session.id,pair.token,answer);relay.answer(session.id,pair.token,answer);const request=relay.host(session.id,{prompt});assert.equal(request.pending.id,answer.id);const ack={prompt:null,result:{id:answer.id,status:'applied'}};relay.host(session.id,ack);relay.host(session.id,ack);assert.deepEqual(relay.answer(session.id,pair.token,answer),{result:ack.result});assert.equal(relay.state(session.id,pair.token).pending,null);});
test('expired heartbeat, stale prompt, conflicting retry and invalid response are rejected',t=>{const {relay,session,pair,advance}=setup(t);relay.host(session.id,{prompt});assert.throws(()=>relay.answer(session.id,pair.token,{...answer,promptId:'old'}));assert.throws(()=>relay.answer(session.id,pair.token,{...answer,response:'invalid'}));relay.answer(session.id,pair.token,answer);assert.throws(()=>relay.answer(session.id,pair.token,{...answer,response:'forgotten'}));relay.host(session.id,{prompt:null,result:{id:answer.id,status:'stale'}});relay.host(session.id,{prompt});advance();assert.equal(relay.state(session.id,pair.token).prompt,null);assert.throws(()=>relay.answer(session.id,pair.token,{...answer,id:'new'}));});
test('closing a session revokes both polling and answers',t=>{const {relay,session,pair}=setup(t);relay.host(session.id,{close:true});assert.throws(()=>relay.state(session.id,pair.token));});

test('HTTP relay restricts host access, supports G2 CORS, and rejects sync access with a paired token',async t=>{
  const {createSyncServer}=await import('../src/http.js');
  const db=openDatabase(':memory:');
  const {server}=createSyncServer({db,token:'host-test-secret'});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{await new Promise(resolve=>server.close(resolve));db.close();});
  const url='http://127.0.0.1:'+server.address().port;
  const post=(path,body,token)=>fetch(url+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body)});
  assert.equal((await post('/g2/create',{})).status,401);
  const session=await (await post('/g2/create',{},'host-test-secret')).json();
  const paired=await (await post('/g2/pair',{code:session.code})).json();
  assert.equal((await post('/g2/host',{id:session.id,prompt},paired.token)).status,401);
  assert.equal((await fetch(url+'/sync/snapshot',{headers:{Authorization:'Bearer '+paired.token}})).status,401);
  const cors=await fetch(url+'/g2/answer',{method:'OPTIONS'});assert.equal(cors.status,204);assert.equal(cors.headers.get('access-control-allow-origin'),'*');
  assert.equal((await post('/g2/host',{id:session.id,prompt},'host-test-secret')).status,200);
  assert.equal((await post('/g2/answer',{sessionId:session.id,...answer},paired.token)).status,200);
});
