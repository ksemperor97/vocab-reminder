import {test} from 'node:test';
import assert from 'node:assert/strict';
import {freshSession,restoreSession,transition,screenText} from './session.js';
const deck=[{id:'a',word:'prior',meaning:'이전의'},{id:'b',word:'recall',meaning:'회상하다'}];
test('reveal does not record an answer; selection confirms once',()=>{
 let s=freshSession(); s=transition(s,'click',deck); assert.equal(s.events.length,0);
 s=transition(s,'down',deck);s=transition(s,'click',deck);
 assert.equal(s.events[0].response,'forgotten');assert.equal(s.phase,'saved');
 s=transition(s,'click',deck); assert.equal(s.events.length,1);assert.equal(s.index,1);
});
test('back hides meaning without recording; selection wraps',()=>{
 let s=transition(freshSession(),'click',deck);s=transition(s,'up',deck);assert.equal(s.selection,2);
 s=transition(s,'back',deck);assert.equal(s.phase,'question');assert.equal(s.events.length,0);
});
test('resume saved card without duplicate; complete deck',()=>{
 let s=freshSession();s=transition(s,'click',deck);s=transition(s,'click',deck);
 s=restoreSession(JSON.stringify(s),deck);s=transition(s,'click',deck);
 s=transition(s,'click',deck);s=transition(s,'click',deck);s=transition(s,'click',deck);
 assert.equal(s.phase,'done');assert.equal(s.events.length,2);
 assert.deepEqual(restoreSession(JSON.stringify(s),deck),s);
 assert.match(screenText(s,deck),/2개 완료/);
 assert.deepEqual(transition(s,'click',deck),s);
});
test('corrupt or mismatched progress fails closed',()=>{
 assert.throws(()=>restoreSession('{',deck));
 assert.throws(()=>restoreSession(JSON.stringify({...freshSession(),index:1}),deck));
 assert.throws(()=>restoreSession(JSON.stringify({...freshSession(),selection:-1}),deck));
});

test('firmware click on sysEvent and scroll on textEvent both route',async()=>{
 const {actionForEvent}=await import('./session.js');
 assert.equal(actionForEvent({sysEvent:{eventSource:1}}),'click');
 assert.equal(actionForEvent({textEvent:{containerID:1,eventType:2}}),'down');
 assert.equal(actionForEvent({textEvent:{containerID:2,eventType:0}}),null);
 assert.equal(actionForEvent({sysEvent:{eventType:4}}),null);
 assert.equal(actionForEvent({sysEvent:{}}),null);
});
