'use strict';
/** Internet mode via Supabase – identical protocol to the Android app (Cloud.kt). */
const crypto = require('crypto');
const WebSocket = require('ws');
const Store = require('./store');
const { Identity, b64, unb64 } = require('./secure');

class Cloud {
  constructor(cfg, store, identity, onMessage, onOnline) {
    this.url = (cfg.SUPABASE_URL || '').replace(/\/+$/, ''); this.key = cfg.SUPABASE_KEY || '';
    this.store = store; this.id = identity; this.onMessage = onMessage; this.onOnline = onOnline;
    this.state = 0; this.keys = new Map(); this.seen = new Map(); this.ref = 1; this.stopped = true;
  }
  get configured() { return this.url.startsWith('https://') && !!this.key; }
  get enabled() { return this.configured && this.store.settings.internet && this.store.settings.registered; }
  headers(extra) { return Object.assign({ apikey: this.key, Authorization: 'Bearer ' + this.key }, extra || {}); }
  phoneHash(p) { return crypto.createHash('sha256').update('uorcall:' + Store.phoneKey(p)).digest('hex'); }
  setState(s) { if (this.state !== s) { this.state = s; this.store.onChange && this.store.onChange(); } }

  start() {
    if (!this.enabled || !this.stopped) return;
    this.stopped = false; this._loop(3000);
  }
  stop() { this.stopped = true; clearTimeout(this._retry); clearInterval(this._hb); try { this.ws && this.ws.terminate(); } catch (e) {} this.setState(0); }
  restart() { this.stop(); this.start(); }

  async _loop(backoff) {
    if (this.stopped || !this.enabled) return this.setState(0);
    this.setState(1);
    let ok = false;
    try { ok = await this.register(); } catch (e) { ok = false; }
    if (!ok) { this._retry = setTimeout(() => this._loop(30000), 30000); return; }
    this._connect(() => { this._retry = setTimeout(() => this._loop(Math.min(backoff * 2, 60000)), backoff); });
  }

  async register() {
    const s = this.store.settings;
    const marker = s.code + ':' + this.id.publicB64.length + this.id.publicB64.slice(-12) + ':' + s.sharePhone;
    if (this.store.misc.cloudReg === marker) return true;
    const r = await fetch(`${this.url}/rest/v1/users?code=eq.${s.code}&select=code,pubkey`, { headers: this.headers() });
    if (!r.ok) return false;
    const a = await r.json();
    if (a.length) {
      if (a[0].pubkey === this.id.publicB64) { this.store.saveMisc({ cloudReg: marker }); return true; }
      this.store.saveSettings({ code: Store.newCode() });   // ID taken by someone else on the internet
      return this.register();
    }
    const body = { code: s.code, name: s.name.slice(0, 60), pubkey: this.id.publicB64,
      phone_hash: s.sharePhone && s.phone ? this.phoneHash(s.phone) : null };
    const w = await fetch(`${this.url}/rest/v1/users`, { method: 'POST', headers: this.headers({ 'Content-Type': 'application/json', Prefer: 'return=minimal' }), body: JSON.stringify(body) });
    if (!w.ok) return false;
    this.store.saveMisc({ cloudReg: marker });
    return true;
  }

  async lookup(q) {
    if (!this.enabled) return null;
    const filter = /^\d{8}$/.test(q) ? `code=eq.${q}` : `phone_hash=eq.${this.phoneHash(q)}`;
    try {
      const r = await fetch(`${this.url}/rest/v1/users?${filter}&select=code,name,pubkey&limit=1`, { headers: this.headers() });
      if (!r.ok) return null;
      const a = await r.json(); if (!a.length) return null;
      this.keys.set(a[0].code, a[0].pubkey);
      return { code: a[0].code, name: a[0].name, key: a[0].pubkey };
    } catch (e) { return null; }
  }
  async registryKey(code) { return this.keys.get(code) || ((await this.lookup(code)) || {}).key || null; }

  _connect(onClosed) {
    const me = this.store.settings.code, topic = 'realtime:u-' + me;
    const ws = new WebSocket(this.url.replace(/^https:/, 'wss:') + `/realtime/v1/websocket?apikey=${this.key}&vsn=1.0.0`);
    this.ws = ws;
    let closed = false;
    const done = () => { if (closed) return; closed = true; clearInterval(this._hb); if (this.state === 2) this.setState(1); onClosed(); };
    ws.on('open', () => {
      ws.send(JSON.stringify({ topic, event: 'phx_join', ref: String(this.ref++), payload: {
        config: { broadcast: { self: false, ack: false }, presence: { key: '' }, private: false }, access_token: this.key } }));
      this._hb = setInterval(() => { try { ws.send(JSON.stringify({ topic: 'phoenix', event: 'heartbeat', payload: {}, ref: String(this.ref++) })); } catch (e) {} }, 25000);
    });
    ws.on('message', (data) => {
      try {
        const o = JSON.parse(data.toString());
        if (o.event === 'phx_reply' && o.topic === topic && o.payload && o.payload.status === 'ok') {
          if (this.state !== 2) { this.setState(2); this.onOnline && this.onOnline(); }
        } else if (o.event === 'broadcast' && o.payload && o.payload.event === 'sig' && o.payload.payload) {
          this._handle(o.payload.payload);
        } else if (o.event === 'phx_error' || o.event === 'phx_close') ws.close();
      } catch (e) {}
    });
    ws.on('close', done); ws.on('error', done);
  }

  async send(to, call, body) {
    if (!this.enabled) return;
    const me = this.store.settings;
    const m = JSON.stringify(Object.assign({}, body, { from: me.code, to, call, ts: Date.now(), n: me.name }));
    const env = { m, s: b64(this.id.sign(Buffer.from(m))), k: this.id.publicB64 };
    const payload = { messages: [{ topic: 'u-' + to, event: 'sig', payload: env, private: false }] };
    try { await fetch(`${this.url}/realtime/v1/api/broadcast`, { method: 'POST', headers: this.headers({ 'Content-Type': 'application/json' }), body: JSON.stringify(payload) }); }
    catch (e) {}
  }

  async _handle(env) {
    const { m, s, k } = env;
    if (!m || !Identity.verify(unb64(k), Buffer.from(m), unb64(s))) return;
    const o = JSON.parse(m);
    if (o.to !== this.store.settings.code || !o.from) return;
    if (Math.abs(Date.now() - o.ts) > 120000) return;
    const id = o.from + ':' + o.ts + ':' + s.slice(-16);
    if (this.seen.has(id)) return;
    this.seen.set(id, Date.now());
    if (this.seen.size > 500) for (const [a, t] of this.seen) if (Date.now() - t > 180000) this.seen.delete(a);
    const reg = await this.registryKey(o.from);
    if (reg && reg !== k) return;                         // someone pretending to be that ID
    const c = this.store.contact(o.from);
    const security = !c || !c.key ? 0 : c.key !== k ? 2 : c.verified ? 1 : 0;
    this.onMessage(o.from, o.call, o, k, security);
  }

  /* offline inbox + encrypted media */
  async insertInbox(id, to, env) {
    try {
      const r = await fetch(`${this.url}/rest/v1/inbox`, { method: 'POST', headers: this.headers({ 'Content-Type': 'application/json', Prefer: 'return=minimal' }),
        body: JSON.stringify({ id, to_code: to, from_code: this.store.settings.code, env }) });
      return r.ok || r.status === 409;
    } catch (e) { return false; }
  }
  async fetchInbox(since) {
    try {
      const me = this.store.settings.code;
      const r = await fetch(`${this.url}/rest/v1/inbox?to_code=eq.${me}&created_at=gt.${encodeURIComponent(since)}&order=created_at.asc&limit=200&select=id,from_code,env,created_at`, { headers: this.headers() });
      return r.ok ? await r.json() : null;
    } catch (e) { return null; }
  }
  async upload(name, bytes) {
    try {
      const r = await fetch(`${this.url}/storage/v1/object/media/${name}`, { method: 'POST', headers: this.headers({ 'Content-Type': 'application/octet-stream' }), body: bytes });
      return r.ok;
    } catch (e) { return false; }
  }
  async download(name) {
    try { const r = await fetch(`${this.url}/storage/v1/object/public/media/${name}`); return r.ok ? Buffer.from(await r.arrayBuffer()) : null; }
    catch (e) { return null; }
  }
}
module.exports = Cloud;
