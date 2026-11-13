'use strict';
/**
 * UoR Call secure protocol – byte-for-byte compatible with the Android app (Crypto.kt).
 *  identity:  ECDSA P-256, public key = X.509 SPKI DER (Base64), signatures = DER (SHA256withECDSA)
 *  handshake: {v:2,t,code,name,phone,id,eph,sig}, sig = ECDSA(identity, eph || type)
 *  session:   HKDF-SHA256(ECDH(eph,eph'), salt "UoRCall-v2", info = ephInitiator||ephResponder, 128 bytes)
 *  frames:    4-byte big-endian length + (8-byte counter || AES-256-GCM(ct||tag)), nonce = [dir,0,0,0] || counter
 */
const crypto = require('crypto');

const b64 = (b) => Buffer.from(b).toString('base64');
const unb64 = (s) => Buffer.from(s, 'base64');

function pubFromDer(der) { return crypto.createPublicKey({ key: Buffer.from(der), format: 'der', type: 'spki' }); }
function spki(pub) { return pub.export({ format: 'der', type: 'spki' }); }

class Identity {
  constructor(saved) {
    if (saved && saved.pub && saved.prv) {
      this.publicKey = pubFromDer(unb64(saved.pub));
      this.privateKey = crypto.createPrivateKey({ key: unb64(saved.prv), format: 'der', type: 'pkcs8' });
    } else {
      const kp = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
      this.publicKey = kp.publicKey; this.privateKey = kp.privateKey;
    }
    this.publicBytes = spki(this.publicKey);
    this.publicB64 = b64(this.publicBytes);
  }
  save() { return { pub: this.publicB64, prv: b64(this.privateKey.export({ format: 'der', type: 'pkcs8' })) }; }
  sign(data) { return crypto.sign('sha256', Buffer.from(data), { key: this.privateKey, dsaEncoding: 'der' }); }
  static verify(pubDer, data, sig) {
    try { return crypto.verify('sha256', Buffer.from(data), { key: pubFromDer(pubDer), dsaEncoding: 'der' }, Buffer.from(sig)); }
    catch (e) { return false; }
  }
  agree(theirPubDer) { return crypto.diffieHellman({ privateKey: this.privateKey, publicKey: pubFromDer(theirPubDer) }); }
  static fingerprint(pubDer) {
    const h = crypto.createHash('sha256').update(Buffer.from(pubDer)).digest();
    return h.subarray(0, 12).toString('hex').toUpperCase().match(/.{4}/g).join(' ');
  }
}

function hkdf(ikm, salt, info, len) { return Buffer.from(crypto.hkdfSync('sha256', ikm, salt, info, len)); }

/** one-shot AES-256-GCM: nonce(12) || ct || tag(16) */
function seal(key, plain) {
  const nonce = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', key, nonce);
  return Buffer.concat([nonce, c.update(plain), c.final(), c.getAuthTag()]);
}
function open(key, data) {
  const d = crypto.createDecipheriv('aes-256-gcm', key, data.subarray(0, 12));
  d.setAuthTag(data.subarray(data.length - 16));
  return Buffer.concat([d.update(data.subarray(12, data.length - 16)), d.final()]);
}

class GcmStream {
  constructor(key, dir) { this.key = key; this.dir = dir; this.sendCtr = 0n; this.lastRecv = -1n; }
  nonce(ctr) { const n = Buffer.alloc(12); n[0] = this.dir; n.writeBigUInt64BE(ctr, 4); return n; }
  seal(plain) {
    this.sendCtr += 1n;
    const c = crypto.createCipheriv('aes-256-gcm', this.key, this.nonce(this.sendCtr));
    const head = Buffer.alloc(8); head.writeBigUInt64BE(this.sendCtr);
    return Buffer.concat([head, c.update(plain), c.final(), c.getAuthTag()]);
  }
  open(msg, strict = true) {
    if (msg.length < 24) return null;
    const ctr = msg.readBigUInt64BE(0);
    if (strict && this.lastRecv !== -1n && ctr !== this.lastRecv + 1n) return null;
    if (!strict && ctr <= this.lastRecv) return null;
    try {
      const d = crypto.createDecipheriv('aes-256-gcm', this.key, this.nonce(ctr));
      d.setAuthTag(msg.subarray(msg.length - 16));
      const out = Buffer.concat([d.update(msg.subarray(8, msg.length - 16)), d.final()]);
      this.lastRecv = ctr;
      return out;
    } catch (e) { return null; }
  }
}

class Session {
  constructor(okm, initiator) {
    const a = okm.subarray(0, 32), b = okm.subarray(32, 64);
    this.tx = new GcmStream(initiator ? a : b, initiator ? 1 : 2);
    this.rx = new GcmStream(initiator ? b : a, initiator ? 2 : 1);
  }
}

/**
 * Length-prefixed frames over a net.Socket with an async reader.
 * getMe() → {code,name,phone,sharePhone}; identity → Identity; contactOf(code) → {key,verified} | null
 */
class SecureChannel {
  constructor(socket, identity, getMe, contactOf) {
    this.socket = socket; this.id = identity; this.getMe = getMe; this.contactOf = contactOf;
    this.session = null; this.remote = null;
    this.buf = Buffer.alloc(0); this.queue = []; this.waiters = []; this.ended = false;
    socket.on('data', (d) => { this.buf = Buffer.concat([this.buf, d]); this._drain(); });
    const end = () => { this.ended = true; this.waiters.splice(0).forEach((w) => w.resolve(null)); };
    socket.on('end', end); socket.on('close', end); socket.on('error', end);
  }
  _drain() {
    while (this.buf.length >= 4) {
      const n = this.buf.readInt32BE(0);
      if (n < 0 || n > 2 * 1024 * 1024) { this.close(); return; }
      if (this.buf.length < 4 + n) return;
      const frame = this.buf.subarray(4, 4 + n); this.buf = this.buf.subarray(4 + n);
      const w = this.waiters.shift();
      if (w) w.resolve(Buffer.from(frame)); else this.queue.push(Buffer.from(frame));
    }
  }
  _rawRecv(timeoutMs) {
    if (this.queue.length) return Promise.resolve(this.queue.shift());
    if (this.ended) return Promise.resolve(null);
    return new Promise((resolve) => {
      const w = { resolve };
      this.waiters.push(w);
      if (timeoutMs) setTimeout(() => { const i = this.waiters.indexOf(w); if (i >= 0) { this.waiters.splice(i, 1); resolve(null); } }, timeoutMs);
    });
  }
  async recv(timeoutMs) {
    const f = await this._rawRecv(timeoutMs);
    if (f == null) return null;
    if (!this.session) return f;
    const p = this.session.rx.open(f);
    if (!p) { this.close(); throw new Error('decrypt failed'); }
    return p;
  }
  async recvJson(timeoutMs) { const b = await this.recv(timeoutMs); return b == null ? null : JSON.parse(b.toString('utf8')); }
  send(data) {
    if (this.ended) return;
    const frame = this.session ? this.session.tx.seal(Buffer.from(data)) : Buffer.from(data);
    const len = Buffer.alloc(4); len.writeInt32BE(frame.length);
    this.socket.write(Buffer.concat([len, frame]));
  }
  sendJson(o) { this.send(Buffer.from(JSON.stringify(o), 'utf8')); }
  close() { this.ended = true; try { this.socket.destroy(); } catch (e) {} }

  _hello(type, eph) {
    const me = this.getMe();
    const ephPub = spki(eph.publicKey);
    return { v: 2, t: type, code: me.code, name: me.name, phone: me.sharePhone ? me.phone : '',
      id: this.id.publicB64, eph: b64(ephPub), sig: b64(this.id.sign(Buffer.concat([ephPub, Buffer.from(type)]))) };
  }
  _parse(o, type) {
    const id = unb64(o.id), eph = unb64(o.eph), sig = unb64(o.sig);
    if (!Identity.verify(id, Buffer.concat([eph, Buffer.from(type)]), sig)) throw new Error('bad signature');
    const key = b64(id);
    const c = this.contactOf(o.code);
    const security = !c || !c.key ? 0 : c.key !== key ? 2 : c.verified ? 1 : 0;
    return [{ code: o.code, name: o.name || o.code, phone: o.phone || '', key, security }, eph];
  }
  _derive(eph, theirEph, initiator) {
    const secret = crypto.diffieHellman({ privateKey: eph.privateKey, publicKey: pubFromDer(theirEph) });
    const mine = spki(eph.publicKey);
    const info = initiator ? Buffer.concat([mine, theirEph]) : Buffer.concat([theirEph, mine]);
    this.session = new Session(hkdf(secret, Buffer.from('UoRCall-v2'), info, 128), initiator);
  }
  /** client side: returns null on success or an error string ("busy", "dnd", "unavailable", "closed") */
  async connectHandshake(type) {
    const eph = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    this.sendJson(this._hello(type, eph));
    const o = await this.recvJson(15000);
    if (!o) return 'closed';
    if (o.err) return o.err;
    const [r, theirEph] = this._parse(o, type);
    this.remote = r;
    this._derive(eph, theirEph, true);
    return null;
  }
  /** server side step 1 */
  async readHello() {
    const o = await this.recvJson(15000);
    if (!o || o.v !== 2) return null;
    const [r, eph] = this._parse(o, o.t);
    this.remote = r;
    return { type: o.t, remote: r, eph };
  }
  accept(type, theirEph) {
    const eph = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    this.sendJson(this._hello(type, eph));
    this._derive(eph, theirEph, false);
  }
  refuse(err) { try { this.sendJson({ err }); } catch (e) {} setTimeout(() => this.close(), 200); }
}

module.exports = { Identity, SecureChannel, GcmStream, Session, hkdf, seal, open, b64, unb64, spki };
