import test from 'node:test';
import assert from 'node:assert/strict';
import {receive,liveText} from './live.js';
test('only matching Android receipt clears an outstanding answer',()=>{const s={pending:{id:'a'},prompt:{id:'p'},selection:2,revealed:true};assert.ok(receive(s,{online:true,prompt:s.prompt,result:{id:'b',status:'applied'}}).pending);const done=receive(s,{online:true,prompt:null,result:{id:'a',status:'applied'}});assert.equal(done.pending,null);assert.match(liveText(done),/저장했습니다/);});
test('new prompt resets answer selection; stale receipt never claims success',()=>{const s={pending:{id:'a'},prompt:{id:'p'},selection:2,revealed:true};const next=receive(s,{online:true,prompt:{id:'next'},result:{id:'a',status:'stale'}});assert.equal(next.selection,0);assert.equal(next.revealed,false);assert.doesNotMatch(liveText(next),/저장했습니다/);});
