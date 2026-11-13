'use strict';
/**
 * Call + remote-support signalling for the desktop app. Same messages as the Android CallService:
 *  invite{video,support} / call{video,support} → ringing → accept → rtc(sdp/ice) … cam, support{mode}, hangup
 * The WebRTC peer connection itself lives in the renderer (Chromium).
 */
const crypto = require('crypto');
const net = require('net');
const { SIGNAL_PORT } = require('./lan');

const RING_TIMEOUT = 45000;
const idle = () => ({ phase: 'idle', peer: null, incoming: false, startedAt: 0, message: '', security: 0, internet: false,
  wantVideo: false, support: { req: false, role: '', mode: '' } });

class LanLink {
  constructor(ch) { this.ch = ch; this.internet = false; }
  send(o) { try { this.ch.sendJson(o); } catch (e) {} }
  close() { setTimeout(() => this.ch.close(), 300); }
}
class CloudLink {
  constructor(cloud, to, call) { this.cloud = cloud; this.to = to; this.call = call; this.internet = true; this.q = Promise.resolve(); }
  send(o) { this.q = this.q.then(() => this.cloud.send(this.to, this.call, o)).catch(() => {}); }
  close() {}
}

class Calls {
  constructor(app) { this.a = app; this.s = idle(); this.link = null; this.timer = null; }
  get store() { return this.a.store; }
  busy() { return this.s.phase !== 'idle' && this.s.phase !== 'ended'; }
  set(patch) { Object.assign(this.s, patch); this.a.callChanged(); }

  /* ---------------- outgoing ---------------- */
  async dial(peer, opts = {}) {
    if (this.busy()) return;
    const video = !!opts.video, support = !!opts.support;
    this.s = Object.assign(idle(), { phase: 'outgoing', peer, message: 'connecting', wantVideo: video,
      support: { req: support, role: support ? 'helper' : '', mode: '' } });
    this.a.callChanged();
    const on = this.a.lan.online()[peer.code];
    let ip = on ? on.ip : null;
    if (!ip && /^\d{8}$/.test(peer.code)) { const r = await this.a.lan.resolve(peer.code, 1500); if (r) ip = r.ip; }
    if (!ip) ip = peer.ip || (this.store.contact(peer.code) || {}).ip || null;
    if (ip && await this.tryLan(peer, ip, video, support)) return;
    if (this.s.phase !== 'outgoing') return;
    if (this.a.cloud.enabled) this.dialInternet(peer, video, support);
    else this.end('unreachable', false);
  }

  connect(ip) {
    return new Promise((resolve) => {
      const s = net.connect({ host: ip, port: SIGNAL_PORT, timeout: 2500 });
      s.once('connect', () => { s.setTimeout(0); s.setNoDelay(true); resolve(s); });
      s.once('timeout', () => { s.destroy(); resolve(null); });
      s.once('error', () => resolve(null));
    });
  }

  async tryLan(peer, ip, video, support) {
    const sock = await this.connect(ip); if (!sock) return false;
    const ch = this.a.messenger.channel(sock);
    let link = null;
    try {
      const err = await ch.connectHandshake('call');
      if (err) { ch.close(); this.end(err === 'busy' ? 'busy' : err === 'dnd' ? 'dnd_on' : 'unavailable', false); return true; }
      if (this.s.phase !== 'outgoing') { ch.close(); return true; }
      const r = ch.remote;
      this.a.messenger.learn(r, ip);
      link = new LanLink(ch); this.link = link;
      this.set({ security: r.security, internet: false,
        peer: { code: r.code, name: (this.store.contact(r.code) || {}).name || r.name, ip, phone: r.phone } });
      link.send({ c: 'invite', video, support });
      this.ringTimeout();
      for (;;) { const o = await ch.recvJson(); if (!o) break; this.onLinkMessage(link, o); }
    } catch (e) {}
    if (link && this.link === link) this.end(this.s.phase === 'outgoing' ? 'unreachable' : 'ended', false);
    return true;
  }

  async dialInternet(peer, video, support) {
    this.set({ internet: true, message: 'calling_internet' });
    const u = await this.a.cloud.lookup(peer.code);
    if (!u) return this.end('unreachable', false);
    if (this.s.phase !== 'outgoing') return;
    const c = this.store.contact(u.code);
    const security = !c || !c.key ? 0 : c.key !== u.key ? 2 : c.verified ? 1 : 0;
    const link = new CloudLink(this.a.cloud, u.code, crypto.randomUUID()); this.link = link;
    this.set({ security, peer: Object.assign({}, this.s.peer, { name: (c && c.name) || u.name || peer.name, ip: '' }) });
    this.ringTimeout();
    for (let i = 0; i < 4; i++) {
      if (this.link !== link || this.s.phase !== 'outgoing' || this.s.message === 'ringing') return;
      link.send({ c: 'call', video, support });
      await new Promise((r) => setTimeout(r, 4000));
    }
  }

  ringTimeout() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      if (this.s.phase === 'outgoing') this.end('no_answer', true);
      else if (this.s.phase === 'incoming') this.end('missed', true);
    }, RING_TIMEOUT);
  }

  /* ---------------- incoming ---------------- */
  async handleIncoming(ch, h, ip) {
    const r = h.remote;
    const caller = { code: r.code, name: (this.store.contact(r.code) || {}).name || r.name, ip, phone: r.phone };
    if (this.store.settings.dnd) { ch.refuse('dnd'); this.missed(caller); return; }
    if (this.busy()) { ch.refuse('busy'); this.missed(caller); return; }
    this.s = Object.assign(idle(), { phase: 'incoming', peer: caller, incoming: true, message: 'incoming', security: r.security });
    ch.accept(h.type, h.eph);
    this.a.messenger.learn(r, ip);
    const inv = await ch.recvJson(8000) || {};
    const link = new LanLink(ch); this.link = link;
    this.set({ wantVideo: !!inv.video, support: { req: !!inv.support, role: inv.support ? 'target' : '', mode: '' } });
    link.send({ c: 'ringing' });
    this.ring();
    try { for (;;) { const o = await ch.recvJson(); if (!o) break; this.onLinkMessage(link, o); } } catch (e) {}
    if (this.link === link) this.end(this.s.phase === 'incoming' ? 'missed' : 'ended', false);
  }

  onCloud(from, call, o, security) {
    const cur = this.link;
    if (o.c === 'inbox') { this.a.messenger.fetchInbox(); return; }
    if (o.c !== 'call') { if (cur instanceof CloudLink && cur.to === from && cur.call === call) this.onLinkMessage(cur, o); return; }
    if (cur instanceof CloudLink && cur.call === call) return;
    const reply = new CloudLink(this.a.cloud, from, call);
    if (!this.store.allowed(from)) return reply.send({ c: 'unavailable' });
    const caller = { code: from, name: (this.store.contact(from) || {}).name || o.n || from, ip: '' };
    if (this.store.settings.dnd) { reply.send({ c: 'dnd' }); this.missed(caller); return; }
    if (this.busy()) { reply.send({ c: 'busy' }); this.missed(caller); return; }
    this.s = Object.assign(idle(), { phase: 'incoming', peer: caller, incoming: true, message: 'incoming', security, internet: true,
      wantVideo: !!o.video, support: { req: !!o.support, role: o.support ? 'target' : '', mode: '' } });
    this.link = reply;
    reply.send({ c: 'ringing' });
    this.ring();
  }

  ring() { this.a.callChanged(); this.a.ringing(this.s); this.ringTimeout(); }
  missed(p) {
    this.store.addHistory({ type: 'missed', code: p.code, name: p.name, ip: p.ip || '', time: Date.now(), duration: 0 });
    this.a.notify('missed_call', p.name + ' • ' + p.code);
  }

  onLinkMessage(link, o) {
    if (this.link !== link) return;
    const ph = this.s.phase;
    switch (o.c) {
      case 'ringing': if (ph === 'outgoing') this.set({ message: 'ringing' }); break;
      case 'accept':
        if (ph === 'outgoing') {
          clearTimeout(this.timer);
          this.set({ phase: 'active', startedAt: Date.now(), message: 'connecting_media' });
          this.a.rtc('start', { caller: true, video: this.s.wantVideo && !this.s.support.req });
        }
        break;
      case 'reject': this.end('declined', false); break;
      case 'busy': this.end('busy', false); break;
      case 'dnd': this.end('dnd_on', false); break;
      case 'unavailable': this.end('unavailable', false); break;
      case 'hangup': this.end(ph === 'incoming' ? 'missed' : 'ended', false); break;
      case 'rtc': this.a.rtc('signal', o); break;
      case 'cam': this.a.rtc('cam', { on: !!o.on }); break;
      case 'support': if (this.s.support.role === 'helper') this.set({ support: Object.assign({}, this.s.support, { mode: o.mode || '' }) }); break;
    }
  }

  /* ---------------- actions from the UI ---------------- */
  accept(video) {
    if (this.s.phase !== 'incoming' || !this.link) return;
    clearTimeout(this.timer);
    this.set({ phase: 'active', startedAt: Date.now(), message: 'connecting_media' });
    this.a.rtc('start', { caller: false, video: !!video });
    this.link.send({ c: 'accept' });
    if (video) this.link.send({ c: 'cam', on: true });
  }
  decline() { if (this.s.phase === 'incoming') this.end('declined', true, 'reject'); }
  hangup() {
    if (this.s.phase === 'incoming') this.decline();
    else if (this.s.phase === 'outgoing' || this.s.phase === 'active') this.end('ended', true);
  }
  send(o) { if (this.link) this.link.send(o); }

  /** Target side: the local user chose view / control / off. */
  setSupportMode(mode) {
    if (this.s.support.role !== 'target' || this.s.phase !== 'active') return;
    this.set({ support: Object.assign({}, this.s.support, { mode }) });
    this.send({ c: 'support', mode });
    this.send({ c: 'cam', on: mode === 'view' || mode === 'control' });
    this.a.supportChanged(mode);
  }

  end(reason, notifyPeer, send = 'hangup') {
    if (this.s.phase === 'idle' || this.s.phase === 'ended') return;
    const snap = Object.assign({}, this.s);
    clearTimeout(this.timer);
    this.a.rtc('end', {});
    if (snap.support.role === 'target') this.a.supportChanged('off');
    const link = this.link; this.link = null;
    if (link) { if (notifyPeer) link.send({ c: send }); link.close(); }
    this.set({ phase: 'ended', message: reason, support: Object.assign({}, snap.support, { mode: '' }) });
    if (snap.peer) {
      const wasActive = snap.phase === 'active';
      const type = !snap.incoming ? 'out' : wasActive ? 'in' : reason === 'declined' ? 'declined' : 'missed';
      this.store.addHistory({ type, code: snap.peer.code, name: snap.peer.name, ip: snap.peer.ip || '', time: Date.now(),
        duration: wasActive ? Math.round((Date.now() - snap.startedAt) / 1000) : 0 });
      if (type === 'missed') this.a.notify('missed_call', snap.peer.name + ' • ' + snap.peer.code);
    }
    setTimeout(() => { if (this.s.phase === 'ended') { this.s = idle(); this.a.callChanged(); this.a.lan.announce(); } }, 2200);
  }
}
module.exports = { Calls };
