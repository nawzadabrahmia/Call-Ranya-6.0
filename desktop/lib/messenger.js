'use strict';
/** Encrypted messages & files: local Wi-Fi first, internet inbox as fallback (same as Messenger.kt). */
const fs = require('fs');
const path = require('path');
const net = require('net');
const crypto = require('crypto');
const { SecureChannel, hkdf, seal, open, b64, unb64 } = require('./secure');
const { SIGNAL_PORT } = require('./lan');

const CHUNK = 60 * 1024, MAX_FILE = 1024 * 1024 * 1024, MAX_CLOUD_FILE = 25 * 1024 * 1024;
const isFile = (k) => ['image', 'video', 'audio', 'file'].includes(k);
const safe = (n) => String(n).replace(/[^A-Za-z0-9._\-\u0600-\u06FF]/g, '_').slice(-80);
const kindOf = (mime) => mime.startsWith('image/') ? 'image' : mime.startsWith('video/') ? 'video' : mime.startsWith('audio/') ? 'audio' : 'file';
const MIME = { '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.gif': 'image/gif', '.webp': 'image/webp',
  '.mp4': 'video/mp4', '.mov': 'video/quicktime', '.mkv': 'video/x-matroska', '.webm': 'video/webm', '.mp3': 'audio/mpeg',
  '.m4a': 'audio/mp4', '.pdf': 'application/pdf', '.doc': 'application/msword',
  '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation', '.txt': 'text/plain', '.zip': 'application/zip' };

class Messenger {
  constructor(app) { this.a = app; this.inFlight = new Set(); this.fetching = false; }
  get store() { return this.a.store; }

  start() {
    setInterval(() => this.retryPending(), 25000);
    setInterval(() => { if (this.a.cloud.state === 2) this.fetchInbox(); }, 60000);
  }

  sendControl(peer, peerName, kind, body) {
    const m = { id: crypto.randomUUID(), peer, peerName, out: true, kind, body, path: '', fileName: '', mime: '', size: 0,
      time: Date.now(), status: 0, read: true };
    this.store.addMessage(m); this.a.changed();
    this.deliver(m);
    return m;
  }
  sendFile(peer, peerName, file, caption) {
    const st = fs.statSync(file);
    if (st.size > MAX_FILE) return 'file_too_big';
    const id = crypto.randomUUID(), name = path.basename(file);
    const dest = path.join(this.store.mediaDir(), id + '_' + safe(name));
    fs.copyFileSync(file, dest);
    const mime = MIME[path.extname(name).toLowerCase()] || 'application/octet-stream';
    const m = { id, peer, peerName, out: true, kind: kindOf(mime), body: caption || '', path: dest, fileName: name, mime,
      size: st.size, time: Date.now(), status: 0, read: true };
    this.store.addMessage(m); this.a.changed();
    this.deliver(m);
    return null;
  }
  retryNow(id) { const m = this.store.msg(id); if (m && m.out && m.status !== 1) this.deliver(m); }
  async retryPending() {
    const pending = this.store.messages.filter((m) => m.out && (m.status === 0 || m.status === 2));
    const byPeer = {};
    pending.forEach((m) => (byPeer[m.peer] = byPeer[m.peer] || []).push(m));
    for (const list of Object.values(byPeer)) for (const m of list) if (!(await this.deliver(m))) break;
  }

  async deliver(m) {
    if (this.inFlight.has(m.id)) return false;
    this.inFlight.add(m.id);
    try {
      let ok = await this.deliverLan(m);
      if (!ok && this.a.cloud.enabled) ok = await this.deliverCloud(m);
      this.store.setStatus(m.id, ok ? 1 : 2); this.a.changed();
      return ok;
    } catch (e) { this.store.setStatus(m.id, 2); this.a.changed(); return false; }
    finally { this.inFlight.delete(m.id); }
  }

  async findIp(code) {
    const on = this.a.lan.online()[code]; if (on) return on.ip;
    const r = await this.a.lan.resolve(code, 1500); if (r) return r.ip;
    const c = this.store.contact(code); return c && c.ip ? c.ip : null;
  }
  connect(ip) {
    return new Promise((resolve) => {
      const s = net.connect({ host: ip, port: SIGNAL_PORT, timeout: 3000 });
      s.once('connect', () => { s.setTimeout(0); s.setNoDelay(true); resolve(s); });
      s.once('timeout', () => { s.destroy(); resolve(null); });
      s.once('error', () => resolve(null));
    });
  }
  channel(sock) { return new SecureChannel(sock, this.a.identity, () => this.store.settings, (c) => this.store.contact(c)); }

  async deliverLan(m) {
    const ip = await this.findIp(m.peer); if (!ip) return false;
    const s = await this.connect(ip); if (!s) return false;
    const ch = this.channel(s);
    try {
      if (await ch.connectHandshake('msg')) return false;
      this.learn(ch.remote, ip);
      ch.sendJson({ id: m.id, kind: m.kind, body: m.body, fname: m.fileName, mime: m.mime, size: m.size, time: m.time });
      if (isFile(m.kind)) {
        const fd = fs.openSync(m.path, 'r'); const buf = Buffer.alloc(CHUNK);
        try { let n; while ((n = fs.readSync(fd, buf, 0, CHUNK, null)) > 0) ch.send(buf.subarray(0, n)); }
        finally { fs.closeSync(fd); }
      }
      const ack = await ch.recvJson(60000);
      return !!ack && ack.ok === m.id;
    } catch (e) { return false; } finally { ch.close(); }
  }

  learn(r, ip) {
    const c = this.store.contact(r.code); if (!c) return;
    this.store.patchContact(r.code, { ip, key: c.key || r.key, phone: c.phone || r.phone });
  }

  /** LAN receive (called by the server for handshake type "msg") */
  async receive(ch, r, ip) {
    this.learn(r, ip);
    for (;;) {
      const meta = await ch.recvJson(60000); if (!meta) break;
      let p = '';
      if (isFile(meta.kind)) {
        if (!(meta.size >= 0 && meta.size <= MAX_FILE)) throw new Error('size');
        p = path.join(this.store.mediaDir(), meta.id + '_' + safe(meta.fname || 'file'));
        const fd = fs.openSync(p, 'w'); let got = 0;
        try { while (got < meta.size) { const c = await ch.recv(60000); if (!c) throw new Error('eof'); fs.writeSync(fd, c); got += c.length; } }
        finally { fs.closeSync(fd); }
      }
      const m = { id: meta.id, peer: r.code, peerName: r.name, out: false, kind: meta.kind || 'text', body: meta.body || '', path: p,
        fileName: meta.fname || '', mime: meta.mime || '', size: meta.size || 0, time: Date.now(), status: 3, read: this.a.openChat === r.code };
      const fresh = this.store.addMessage(m);
      ch.sendJson({ ok: meta.id });
      if (fresh) this.a.incomingMessage(m);
    }
  }

  /* ---------- internet ---------- */
  msgKey(theirKey, id) { return hkdf(this.a.identity.agree(unb64(theirKey)), Buffer.from(id), Buffer.from('uorcall-msg-v1'), 32); }

  async deliverCloud(m) {
    const key = await this.a.cloud.registryKey(m.peer); if (!key) return false;
    const c = this.store.contact(m.peer);
    if (c && c.key && c.key !== key) return false;
    const payload = { kind: m.kind, body: m.body, fname: m.fileName, mime: m.mime, size: m.size, time: m.time };
    if (isFile(m.kind)) {
      if (m.size > MAX_CLOUD_FILE) return false;
      const fk = crypto.randomBytes(32), blob = crypto.randomUUID().replace(/-/g, '');
      if (!(await this.a.cloud.upload(blob, seal(fk, fs.readFileSync(m.path))))) return false;
      payload.blob = blob; payload.fk = b64(fk);
    }
    const me = this.store.settings;
    const inner = JSON.stringify({ from: me.code, to: m.peer, id: m.id, n: me.name, ts: Date.now(),
      c: b64(seal(this.msgKey(key, m.id), Buffer.from(JSON.stringify(payload), 'utf8'))) });
    const env = { m: inner, s: b64(this.a.identity.sign(Buffer.from(inner))), k: this.a.identity.publicB64 };
    if (!(await this.a.cloud.insertInbox(m.id, m.peer, JSON.stringify(env)))) return false;
    this.a.cloud.send(m.peer, 'msg', { c: 'inbox' });
    return true;
  }

  async fetchInbox() {
    if (!this.a.cloud.enabled || this.fetching) return;
    this.fetching = true;
    try {
      const since = this.store.misc.inboxSince || '2000-01-01T00:00:00Z';
      const rows = await this.a.cloud.fetchInbox(since); if (!rows) return;
      let last = since;
      for (const r of rows) { last = r.created_at || last; try { await this.receiveCloud(r.id, r.from_code, JSON.parse(r.env)); } catch (e) {} }
      if (last !== since) this.store.saveMisc({ inboxSince: last });
    } finally { this.fetching = false; }
  }

  async receiveCloud(id, from, env) {
    const { Identity } = require('./secure');
    if (this.store.msg(id) || !this.store.allowed(from)) return;
    if (!Identity.verify(unb64(env.k), Buffer.from(env.m), unb64(env.s))) return;
    const reg = await this.a.cloud.registryKey(from); if (reg && reg !== env.k) return;
    const o = JSON.parse(env.m);
    if (o.from !== from || o.id !== id || o.to !== this.store.settings.code) return;
    const p = JSON.parse(open(this.msgKey(env.k, id), unb64(o.c)).toString('utf8'));
    let file = '';
    if (isFile(p.kind)) {
      const data = await this.a.cloud.download(p.blob); if (!data) return;
      file = path.join(this.store.mediaDir(), id + '_' + safe(p.fname || 'file'));
      fs.writeFileSync(file, open(unb64(p.fk), data));
    }
    const c = this.store.contact(from); if (c && !c.key) this.store.patchContact(from, { key: env.k });
    const m = { id, peer: from, peerName: o.n || from, out: false, kind: p.kind || 'text', body: p.body || '', path: file,
      fileName: p.fname || '', mime: p.mime || '', size: p.size || 0, time: Date.now(), status: 3, read: this.a.openChat === from };
    if (this.store.addMessage(m)) this.a.incomingMessage(m);
  }
}
module.exports = { Messenger, isFile };
