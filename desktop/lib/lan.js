'use strict';
/** Local Wi-Fi presence (UDP 45454) – same privacy rules and JSON messages as the Android app. */
const dgram = require('dgram');
const os = require('os');
const Store = require('./store');

const DISCOVERY_PORT = 45454, SIGNAL_PORT = 45455;

function interfaces() {
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      const ip = a.address.split('.').map(Number), mask = a.netmask.split('.').map(Number);
      out.push({ name, ip: a.address, broadcast: ip.map((b, i) => (b | (~mask[i] & 255))).join('.') });
    }
  }
  return out;
}
function localIp() {
  const l = interfaces().filter((i) => /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(i.ip));
  const wifi = l.find((i) => /wi-?fi|wlan|wireless/i.test(i.name));
  return (wifi || l[0] || {}).ip || '';
}

class Lan {
  constructor(store, events) {
    this.store = store; this.ev = events;
    this.visible = new Map(); this.reach = new Map(); this.conflictUntil = 0; this.myIp = '';
  }
  status() { const s = this.store.settings; return this.ev.isBusy() ? 1 : s.dnd ? 2 : 0; }
  start() {
    this.sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.sock.on('message', (msg, rinfo) => { try { this._handle(JSON.parse(msg.toString('utf8')), rinfo.address); } catch (e) {} });
    this.sock.on('error', () => {});
    this.sock.bind(DISCOVERY_PORT, () => { try { this.sock.setBroadcast(true); } catch (e) {} });
    this._ann = setInterval(() => this.announce(), 3000);
    this._prune = setInterval(() => this._pruneTick(), 3000);
    this._contacts = setInterval(() => this._pingContacts(), 20000);
    setTimeout(() => this.announce(), 500);
  }
  _send(o, addrs) {
    const b = Buffer.from(JSON.stringify(o), 'utf8');
    for (const a of addrs) { try { this.sock.send(b, DISCOVERY_PORT, a); } catch (e) {} }
  }
  broadcasts() { return [...new Set(interfaces().map((i) => i.broadcast).concat(['255.255.255.255']))]; }
  announce() {
    this.myIp = localIp(); this.ev.changed();
    const s = this.store.settings;
    if (!s.registered || !s.visible) return;
    this._send({ t: 'ann', c: s.code, n: s.name, p: s.sharePhone ? s.phone : '', s: this.status() }, this.broadcasts());
  }
  _handle(o, ip) {
    const me = this.store.settings;
    if (!o.c || !me.registered) return;
    if (o.c === me.code) { if (ip !== this.myIp && this.myIp) { this.conflictUntil = Date.now() + 10000; this.ev.changed(); } return; }
    const now = Date.now();
    if (o.t === 'ann') {
      for (const [k, v] of this.visible) if (v.ip === ip && k !== o.c) this.visible.delete(k);
      this.visible.set(o.c, { code: o.c, name: o.n || o.c, ip, phone: o.p || '', lastSeen: now, status: o.s | 0, visible: true });
      this.ev.changed();
    } else if (o.t === 'iam') {
      this.reach.set(o.c, { code: o.c, name: o.n || o.c, ip, phone: o.p || '', lastSeen: now, status: o.s | 0, visible: !!o.v });
      const c = this.store.contact(o.c); if (c && c.ip !== ip) this.store.patchContact(o.c, { ip });
      this.ev.changed();
    } else if (o.t === 'who') {
      const q = String(o.q || '');
      const match = q === me.code || (me.phone && Store.phoneKey(q).length >= 7 && Store.phoneKey(q) === Store.phoneKey(me.phone));
      if (!match || !this.store.allowed(o.c)) return;
      this._send({ t: 'iam', c: me.code, n: me.name, p: me.sharePhone ? me.phone : '', s: this.status(), v: me.visible }, [ip]);
    }
  }
  _pruneTick() {
    const now = Date.now(); let ch = false;
    for (const [k, v] of this.visible) if (now - v.lastSeen > 10000) { this.visible.delete(k); ch = true; }
    for (const [k, v] of this.reach) if (now - v.lastSeen > 50000) { this.reach.delete(k); ch = true; }
    if (ch) this.ev.changed();
  }
  _pingContacts() {
    const me = this.store.settings; if (!me.registered) return;
    this.store.contacts.filter((c) => !c.blocked && !this.visible.has(c.code)).slice(0, 40)
      .forEach((c, i) => setTimeout(() => this._send({ t: 'who', q: c.code, c: me.code }, this.broadcasts()), i * 120));
  }
  peers() { return [...this.visible.values()].filter((p) => !this.store.isBlocked(p.code)).sort((a, b) => a.name.localeCompare(b.name)); }
  online() { const m = {}; for (const v of this.reach.values()) m[v.code] = v; for (const v of this.visible.values()) m[v.code] = v; return m; }
  conflict() { return Date.now() < this.conflictUntil; }
  /** find a phone by exact code or phone number */
  async resolve(q, timeoutMs = 2500) {
    q = String(q).trim();
    const isCode = /^\d{8}$/.test(q);
    const known = () => Object.values(this.online()).find((p) => isCode ? p.code === q : (p.phone && Store.phoneKey(p.phone) === Store.phoneKey(q)));
    let k = known(); if (k && Date.now() - k.lastSeen < 8000) return k;
    const me = this.store.settings; const until = Date.now() + timeoutMs; let n = 0;
    while (Date.now() < until) {
      if (n++ % 4 === 0) this._send({ t: 'who', q, c: me.code }, this.broadcasts());
      await new Promise((r) => setTimeout(r, 150));
      k = known(); if (k && Date.now() - k.lastSeen < 3000) return k;
    }
    return null;
  }
}
module.exports = { Lan, SIGNAL_PORT, localIp };
