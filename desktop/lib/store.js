'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

/** Local storage in %APPDATA%/UoR Call (JSON files). Same data model as the Android app. */
class Store {
  constructor(dir) {
    this.dir = dir;
    fs.mkdirSync(path.join(dir, 'media'), { recursive: true });
    this.settings = Object.assign({
      registered: false, code: Store.newCode(), name: '', phone: '', lang: 'en', theme: 'system',
      visible: false, sharePhone: false, whoCanCall: 'everyone', dnd: false, autoStart: true, internet: true, org: ''
    }, this._read('settings.json', {}));
    this.contacts = this._read('contacts.json', []);
    this.history = this._read('history.json', []);
    this.messages = this._read('messages.json', []);
    this.rollcalls = this._read('rollcalls.json', []);
    this.misc = this._read('misc.json', {});
    this._timers = {};
  }
  static newCode() { return String(10000000 + crypto.randomInt(90000000)); }
  static formatCode(c) { return c && c.length === 8 ? c.slice(0, 4) + ' ' + c.slice(4) : c; }
  static phoneKey(p) { return String(p || '').replace(/\D/g, '').slice(-10); }
  mediaDir() { return path.join(this.dir, 'media'); }
  _read(f, def) { try { return JSON.parse(fs.readFileSync(path.join(this.dir, f), 'utf8')); } catch (e) { return def; } }
  _write(f, v) {
    clearTimeout(this._timers[f]);
    this._timers[f] = setTimeout(() => {
      const p = path.join(this.dir, f);
      fs.writeFileSync(p + '.tmp', JSON.stringify(v));
      fs.renameSync(p + '.tmp', p);
    }, 150);
  }
  saveSettings(patch) { Object.assign(this.settings, patch); this._write('settings.json', this.settings); }
  saveMisc(patch) { Object.assign(this.misc, patch); this._write('misc.json', this.misc); }

  contact(code) { return this.contacts.find((c) => c.code === code) || null; }
  upsertContact(c) {
    const old = this.contact(c.code);
    const merged = old ? Object.assign({}, old, {
      name: c.name || old.name, phone: c.phone || old.phone, ip: c.ip || old.ip, key: c.key || old.key,
      verified: !!(c.verified || (old.verified && (!c.key || c.key === old.key))), team: !!(c.team || old.team)
    }) : Object.assign({ name: c.code, phone: '', ip: '', key: '', verified: false, blocked: false, team: false }, c);
    this.contacts = this.contacts.filter((x) => x.code !== c.code).concat([merged])
      .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
    this._write('contacts.json', this.contacts);
  }
  patchContact(code, patch) {
    const old = this.contact(code) || { code, name: patch.name || code, phone: '', ip: '', key: '', verified: false, blocked: false, team: false };
    this.contacts = this.contacts.filter((x) => x.code !== code).concat([Object.assign({}, old, patch)])
      .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
    this._write('contacts.json', this.contacts);
  }
  removeContact(code) { this.contacts = this.contacts.filter((x) => x.code !== code); this._write('contacts.json', this.contacts); }
  isBlocked(code) { const c = this.contact(code); return !!(c && c.blocked); }
  allowed(code) {
    if (this.isBlocked(code)) return false;
    if (this.settings.whoCanCall === 'contacts' && !this.contact(code)) return false;
    return true;
  }
  team() { return this.contacts.filter((c) => c.team && !c.blocked); }

  addHistory(h) { this.history = [h].concat(this.history).slice(0, 300); this._write('history.json', this.history); }
  clearHistory() { this.history = []; this._write('history.json', this.history); }

  msg(id) { return this.messages.find((m) => m.id === id) || null; }
  addMessage(m) { if (this.msg(m.id)) return false; this.messages.push(m); this._write('messages.json', this.messages); return true; }
  setStatus(id, status) { const m = this.msg(id); if (m) { m.status = status; this._write('messages.json', this.messages); } }
  markRead(peer) { let ch = false; this.messages.forEach((m) => { if (m.peer === peer && !m.out && !m.read) { m.read = true; ch = true; } }); if (ch) this._write('messages.json', this.messages); }
  deleteChat(peer) {
    this.messages.filter((m) => m.peer === peer && m.path).forEach((m) => { try { fs.unlinkSync(m.path); } catch (e) {} });
    this.messages = this.messages.filter((m) => m.peer !== peer); this._write('messages.json', this.messages);
  }
  saveRollcalls(list) { this.rollcalls = list.slice(0, 20); this._write('rollcalls.json', this.rollcalls); }
}
module.exports = Store;
