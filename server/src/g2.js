import crypto from 'node:crypto';
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const fail = (status, message) => { throw Object.assign(new Error(message), {status}); };
const text = (value, max=200) => typeof value === 'string' && value.length > 0 && value.length <= max;
export class G2Relay {
  constructor(db, now=Date.now) {
    this.db=db; this.now=now;
    db.exec(`CREATE TABLE IF NOT EXISTS g2_sessions (id TEXT PRIMARY KEY, body TEXT NOT NULL);`);
  }
  get(id) { const row=this.db.prepare('SELECT body FROM g2_sessions WHERE id=?').get(id); return row ? JSON.parse(row.body) : null; }
  put(s) { this.db.prepare('INSERT OR REPLACE INTO g2_sessions VALUES (?,?)').run(s.id,JSON.stringify(s)); }
  create() {
    // Expired pairing/session data is temporary; real review records live on Android.
    for (const row of this.db.prepare('SELECT id,body FROM g2_sessions').all()) {
      if (JSON.parse(row.body).expires < this.now()) this.db.prepare('DELETE FROM g2_sessions WHERE id=?').run(row.id);
    }
    const code=crypto.randomBytes(6).toString('hex').toUpperCase();
    const s={id:crypto.randomUUID(),codeHash:hash(code),pairUntil:this.now()+600000,expires:this.now()+30*86400000,prompt:null,heartbeat:0,pending:null,result:null};
    this.put(s); return {id:s.id,code};
  }
  pair(code) {
    if (!text(code,20)) fail(400,'Enter the pairing code.');
    const digest=hash(code.toUpperCase().replaceAll('-',''));
    const s=this.db.prepare('SELECT body FROM g2_sessions').all().map(r=>JSON.parse(r.body)).find(s=>s.codeHash===digest && s.pairUntil>this.now());
    if(!s) fail(401,'Pairing code expired or invalid.');
    const token=crypto.randomBytes(32).toString('hex');
    s.codeHash=null; s.tokenHash=hash(token); this.put(s); return {id:s.id,token};
  }
  session(id, token, host=false) {
    if(!text(id,40)) fail(400,'Invalid session.');
    const s=this.get(id);
    if(!s || s.expires<this.now() || (!host && (!token || s.tokenHash!==hash(token)))) fail(401,'Reconnect from the Android app.');
    return s;
  }
  host(id, body) {
    const s=this.session(id,null,true);
    if(body.close===true) { this.db.prepare('DELETE FROM g2_sessions WHERE id=?').run(id); return {ok:true}; }
    const p=body.prompt;
    if(p!==null && (!p || !text(p.id) || !text(p.cardId) || !text(p.revision) || !text(p.word,120) || typeof p.meaning!=='string' || p.meaning.length>2000)) fail(400,'Invalid prompt.');
    if(body.result) {
      if ((!s.pending || body.result.id!==s.pending.id) && (s.result?.id!==body.result.id || s.result?.status!==body.result.status)) fail(409,'Unexpected result.');
      if (!['applied','stale'].includes(body.result.status)) fail(400,'Invalid result.');
      s.result={id:body.result.id,status:body.result.status}; s.pending=null;
    }
    s.prompt=p; s.heartbeat=this.now(); this.put(s);
    return {pending:s.pending,paired:!!s.tokenHash};
  }
  state(id,token) {
    const s=this.session(id,token);
    return {online:this.now()-s.heartbeat<20000,prompt:this.now()-s.heartbeat<20000?s.prompt:null,result:s.result,pending:s.pending};
  }
  answer(id,token,body) {
    return this.db.transaction(()=>{
      const s=this.session(id,token);
      if(!text(body.id,80) || !text(body.promptId) || !['remembered','forgotten','unavailable'].includes(body.response)) fail(400,'Invalid answer.');
      if(s.result?.id===body.id) return {result:s.result};
      if(s.pending?.id===body.id) {
        if(s.pending.promptId!==body.promptId || s.pending.response!==body.response) fail(409,'Answer ID already used.');
        return {pending:true};
      }
      if(s.pending || this.now()-s.heartbeat>=20000 || s.prompt?.id!==body.promptId) fail(409,'This prompt is no longer active.');
      s.pending={id:body.id,promptId:body.promptId,response:body.response,cardId:s.prompt.cardId,revision:s.prompt.revision};
      this.put(s); return {pending:true};
    })();
  }
}
