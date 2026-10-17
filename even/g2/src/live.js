export const responses=['remembered','forgotten','unavailable'];
export const labels=['외움','잊음','나중에'];
export function liveText(s) {
  if(s.pending) return 'G2 복습\n\n응답 전송 중…\nAndroid 저장 확인을 기다립니다.\n연결이 돌아오면 자동 재시도합니다.';
  if(s.message) return `G2 복습\n\n${s.message}\n\n탭: 계속`;
  if(!s.online) return 'G2 복습\n\n휴대폰 연결을 기다립니다.\nVocabulary Reminder의 G2 연결을\n시작해 주세요.';
  if(!s.prompt) return 'G2 복습\n\n다음 복습을 기다립니다.\n문제가 도착하면 여기에 표시됩니다.\n\n두 번 탭: 종료';
  if(!s.revealed) return `G2 복습\n\n${s.prompt.word}\n\n뜻을 떠올려 보세요.\n탭: 뜻 보기`;
  return `G2 복습 · ${s.prompt.word}\n${s.prompt.meaning.slice(0,130)}\n\n${labels.map((x,i)=>(i===s.selection?'▶ ':'  ')+x).join('   ')}\n\n위아래: 선택 / 탭: 확정`;
}
export function receive(s,remote) {
  const next={...s,online:remote.online,prompt:remote.prompt};
  if(s.pending && remote.result?.id===s.pending.id) {
    next.pending=null; next.message=remote.result.status==='applied'?'실제 복습 기록에 저장했습니다.':'이미 변경된 문제입니다. 다시 불러옵니다.';
  }
  if(s.prompt?.id!==remote.prompt?.id) { next.revealed=false;next.selection=0; }
  return next;
}
