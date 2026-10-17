export const choices = [
  {code:'remembered',label:'외움'},
  {code:'forgotten',label:'잊음'},
  {code:'unavailable',label:'나중에'},
];
export function freshSession() { return {version:1,index:0,phase:'question',selection:0,events:[]}; }
export function restoreSession(raw, deck) {
  if (!raw) return freshSession();
  const s=JSON.parse(raw);
  if (s.version!==1 || !Number.isInteger(s.index) || s.index<0 || s.index>deck.length ||
      !['question','answer','saved','done'].includes(s.phase) || !Number.isInteger(s.selection) || s.selection<0 || s.selection>2 ||
      !Array.isArray(s.events) || s.events.length>deck.length ||
      s.events.some((e,i)=>e.cardId!==deck[i]?.id || !choices.some(c=>c.code===e.response) || !Number.isFinite(Date.parse(e.at)))) throw new Error('시험 기록 형식이 올바르지 않습니다.');
  const expected=s.index+(s.phase==='saved'?1:0);
  if(s.events.length!==expected || (s.phase==='done')!==(s.index===deck.length)) throw new Error('시험 기록 순서가 올바르지 않습니다.');
  return s;
}
export function transition(state, action, deck, at=new Date().toISOString()) {
  const s=structuredClone(state);
  if(action==='back') { if(s.phase==='answer') s.phase='question'; return s; }
  if(s.phase==='answer' && (action==='up'||action==='down')) {
    s.selection=(s.selection+(action==='up'?2:1))%3;
  } else if(action==='click') {
    if(s.phase==='question') { s.phase='answer'; s.selection=0; }
    else if(s.phase==='answer') {
      s.events.push({cardId:deck[s.index].id,response:choices[s.selection].code,at,source:'even_g2_trial'});
      s.phase='saved';
    } else if(s.phase==='saved') {
      s.index++; s.phase=s.index===deck.length?'done':'question'; s.selection=0;
    }
  }
  return s;
}
export function screenText(s,deck) {
  const title='링 학습 시험 · 실제 기록 반영 안 됨';
  if(s.phase==='done') return `${title}\n\n${deck.length}개 완료!\n시험 응답 ${s.events.length}건 저장됨\n\n두 번 탭: 종료`;
  const c=deck[s.index];
  const heading=`${title}\n${s.index+1} / ${deck.length}    ${c.word}`;
  if(s.phase==='question') return `${heading}\n\n뜻을 떠올려 보세요.\n\n탭: 뜻 보기\n두 번 탭: 종료`;
  if(s.phase==='saved') return `${heading}\n\n${choices.find(c=>c.code===s.events.at(-1).response).label} · 시험 기록 저장됨\n\n탭: 다음 단어\n두 번 탭: 종료`;
  return `${heading}\n${c.meaning}\n\n${choices.map((c,i)=>(s.selection===i?'▶ ':'  ')+c.label).join('   ')}\n\n위아래: 선택  /  탭: 확정`;
}

export function actionForEvent(event) {
  // Firmware may send click as sysEvent with source only (enum 0 omitted).
  const e=event.textEvent ?? event.sysEvent;
  if(!e) return null;
  if(event.textEvent && e.containerID!==1) return null;
  if(!event.textEvent && e.eventType==null && e.eventSource==null) return null;
  return ({0:'click',1:'up',2:'down',3:'back'})[e.eventType??0]??null;
}
