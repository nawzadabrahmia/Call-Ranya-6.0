'use strict';
/**
 * Injects mouse/keyboard on Windows for remote support. The helper process runs ONLY while the
 * local user has explicitly allowed control, and is killed the moment control stops.
 */
const { spawn } = require('child_process');
const path = require('path');

const VK = { Backspace: 8, Tab: 9, Enter: 13, NumpadEnter: 13, ShiftLeft: 16, ShiftRight: 16, ControlLeft: 17, ControlRight: 17,
  AltLeft: 18, AltRight: 18, Pause: 19, CapsLock: 20, Escape: 27, Space: 32, PageUp: 33, PageDown: 34, End: 35, Home: 36,
  ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40, PrintScreen: 44, Insert: 45, Delete: 46, MetaLeft: 91, MetaRight: 92,
  ContextMenu: 93, NumLock: 144, ScrollLock: 145, Semicolon: 186, Equal: 187, Comma: 188, Minus: 189, Period: 190, Slash: 191,
  Backquote: 192, BracketLeft: 219, Backslash: 220, BracketRight: 221, Quote: 222 };
for (let i = 0; i < 26; i++) VK['Key' + String.fromCharCode(65 + i)] = 65 + i;
for (let i = 0; i < 10; i++) { VK['Digit' + i] = 48 + i; VK['Numpad' + i] = 96 + i; }
for (let i = 1; i <= 12; i++) VK['F' + i] = 111 + i;

class Input {
  constructor(resourcesDir) { this.script = path.join(resourcesDir, 'input.ps1'); this.proc = null; this.down = new Set(); }
  start() {
    if (process.platform !== 'win32' || this.proc) return;
    this.proc = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', this.script],
      { windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'] });
    this.proc.on('exit', () => { this.proc = null; });
  }
  stop() {
    if (!this.proc) return;
    // release anything still pressed so the user never ends up with a stuck key/button
    for (const k of this.down) { const [t, v] = k.split(':'); this._w(t === 'b' ? `U ${v}` : `K ${v} 0`); }
    this.down.clear();
    this._w('Q'); const p = this.proc; this.proc = null; setTimeout(() => { try { p.kill(); } catch (e) {} }, 500);
  }
  _w(line) { try { this.proc && this.proc.stdin.write(line + '\n'); } catch (e) {} }
  _xy(x, y) { const c = (v) => Math.min(1, Math.max(0, Number(v) || 0)).toFixed(5); return `M ${c(x)} ${c(y)}`; }

  /** Events from the helper (phone or PC). Coordinates are 0..1 of the shared screen. */
  handle(e) {
    if (!this.proc) return;
    switch (e.t) {
      case 'move': this._w(this._xy(e.x, e.y)); break;
      case 'down': this._w(this._xy(e.x, e.y)); this._w(`D ${e.b | 0}`); this.down.add('b:' + (e.b | 0)); break;
      case 'up': this._w(this._xy(e.x, e.y)); this._w(`U ${e.b | 0}`); this.down.delete('b:' + (e.b | 0)); break;
      case 'tap': this._w(this._xy(e.x, e.y)); this._w('D 0'); this._w('U 0'); break;
      case 'long': this._w(this._xy(e.x, e.y)); this._w('D 2'); this._w('U 2'); break;           // long-press = right click
      case 'swipe': {
        this._w(this._xy(e.x1, e.y1)); this._w('D 0');
        for (let i = 1; i <= 10; i++) this._w(this._xy(e.x1 + (e.x2 - e.x1) * i / 10, e.y1 + (e.y2 - e.y1) * i / 10));
        this._w('U 0'); break;
      }
      case 'wheel': this._w(`W ${Math.max(-1200, Math.min(1200, -Math.round(e.dy)))}`); break;
      case 'kd': case 'ku': {
        const vk = VK[e.code]; if (!vk) break;
        this._w(`K ${vk} ${e.t === 'kd' ? 1 : 0}`);
        if (e.t === 'kd') this.down.add('k:' + vk); else this.down.delete('k:' + vk);
        break;
      }
      case 'text': if (e.s) this._w('T ' + Buffer.from(String(e.s).slice(0, 500), 'utf8').toString('base64')); break;
      case 'key': {
        const vk = { back: 27, home: 91, recents: 9, enter: 13, backspace: 8 }[e.k];
        if (e.k === 'recents') { this._w('K 18 1'); this._w('K 9 1'); this._w('K 9 0'); this._w('K 18 0'); }   // Alt+Tab
        else if (vk) { this._w(`K ${vk} 1`); this._w(`K ${vk} 0`); }
        break;
      }
    }
  }
}
module.exports = Input;
