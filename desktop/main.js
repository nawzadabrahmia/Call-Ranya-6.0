'use strict';
const { app, BrowserWindow, Tray, Menu, ipcMain, Notification, dialog, shell, session, desktopCapturer, powerSaveBlocker, nativeImage, screen } = require('electron');
const path = require('path');
const fs = require('fs');
const net = require('net');
const QRCode = require('qrcode');
const Store = require('./lib/store');
const { Identity } = require('./lib/secure');
const { Lan, SIGNAL_PORT } = require('./lib/lan');
const Cloud = require('./lib/cloud');
const { Messenger } = require('./lib/messenger');
const { Calls } = require('./lib/calls');
const Input = require('./lib/input');

if (process.env.UOR_TEST_DATA) app.setPath('userData', process.env.UOR_TEST_DATA);   // screenshot tests only
// Renamed to "Call Ranya" in 6.0: keep using the old data folder (%APPDATA%\UoR Call) so contacts, chats and keys survive the update.
else app.setPath('userData', path.join(app.getPath('appData'), 'UoR Call'));
if (!app.requestSingleInstanceLock()) { app.quit(); process.exit(0); }
app.setAppUserModelId('edu.uor.lancall.desktop');

const RES = app.isPackaged ? process.resourcesPath : __dirname;
const cfg = (() => { for (const p of [path.join(RES, 'config.json'), path.join(__dirname, 'config.json')]) { try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (e) {} } return {}; })();

const A = { openChat: null, allowScreen: false };
let win = null, tray = null, banner = null, quitting = false, changeTimer = null;

/* ---------------- app object shared by the modules ---------------- */
A.changed = () => { clearTimeout(changeTimer); changeTimer = setTimeout(() => send('changed', snapshot()), 120); };
A.callChanged = () => { send('call', A.calls.s); A.changed(); };
A.rtc = (kind, data) => {
  if (kind === 'start') data.iceServers = iceServers();
  if (kind === 'end') A.allowScreen = false;
  send('rtc:' + kind, data);
};
A.ringing = (s) => {
  showWindow(); if (win) win.flashFrame(true);
  const n = new Notification({ title: s.peer.name, body: (s.support.req ? '🛟 ' : s.wantVideo ? '🎥 ' : '📞 ') + s.peer.code, urgency: 'critical' });
  n.on('click', showWindow); n.show();
};
A.notify = (key, body, onClick) => {
  const n = new Notification({ title: tr(key), body });
  n.on('click', () => { showWindow(); if (onClick) onClick(); }); n.show();
};
A.incomingMessage = (m) => {
  A.changed();
  if (m.kind === 'rollcall') return A.notify('rollcall', (A.store.contact(m.peer) || {}).name || m.peerName, () => send('open-chat', m.peer));
  if (m.kind === 'checkin') {
    let o = {}; try { o = JSON.parse(m.body); } catch (e) {}
    const n = new Notification({ title: ((A.store.contact(m.peer) || {}).name || m.peerName),
      body: (o.status === 'help' ? '🆘 ' + tr('need_help') : '✅ ' + tr('im_safe')) + (o.note ? ' – ' + o.note : ''),
      urgency: o.status === 'help' ? 'critical' : 'normal' });
    n.on('click', () => { showWindow(); send('open-chat', m.peer); }); n.show();
    if (o.status === 'help') { showWindow(); win && win.flashFrame(true); send('sos', m.peer); }
    return;
  }
  if (A.openChat === m.peer && win && win.isFocused()) return;
  const txt = m.kind === 'text' ? m.body : m.kind === 'image' ? '📷' : m.kind === 'video' ? '🎬' : '📎 ' + m.fileName;
  const n = new Notification({ title: (A.store.contact(m.peer) || {}).name || m.peerName, body: txt });
  n.on('click', () => { showWindow(); send('open-chat', m.peer); }); n.show();
};
/** Remote support on THIS PC: show the red banner, start/stop input injection. */
A.supportChanged = (mode) => {
  A.allowScreen = mode === 'view' || mode === 'control';
  if (mode === 'control') A.input.start(); else A.input.stop();
  if (mode === 'view' || mode === 'control') showBanner(mode); else hideBanner();
  send('support:local', mode);
};

function tr(k) { try { return require('./renderer/i18n.js').t(A.store.settings.lang, k); } catch (e) { return k; } }
function send(ch, data) { if (win && !win.isDestroyed()) win.webContents.send(ch, data); }
function iceServers() {
  const l = [{ urls: ['stun:stun.l.google.com:19302', 'stun:stun.cloudflare.com:3478'] }];
  const turn = String(cfg.TURN_URLS || '').split(',').map((s) => s.trim()).filter(Boolean);
  if (turn.length) l.push({ urls: turn, username: cfg.TURN_USER, credential: cfg.TURN_PASS });
  return l;
}

function snapshot() {
  const s = A.store;
  const convs = {};
  for (const m of s.messages) {
    const c = convs[m.peer] || (convs[m.peer] = { peer: m.peer, name: (s.contact(m.peer) || {}).name || m.peerName, last: m, unread: 0 });
    if (m.time >= c.last.time) c.last = m;
    if (!m.out && !m.read) c.unread++;
  }
  return {
    settings: s.settings, contacts: s.contacts, history: s.history, rollcalls: s.rollcalls,
    peers: A.lan.peers(), online: A.lan.online(), myIp: A.lan.myIp, conflict: A.lan.conflict(),
    cloud: A.cloud.state, cloudConfigured: A.cloud.configured, fingerprint: Identity.fingerprint(A.identity.publicBytes),
    conversations: Object.values(convs).sort((a, b) => b.last.time - a.last.time), call: A.calls.s,
    checkins: s.messages.filter((m) => m.kind === 'checkin' && !m.out)
  };
}

/* ---------------- windows ---------------- */
function createWindow(hidden) {
  win = new BrowserWindow({
    width: 1180, height: 780, minWidth: 900, minHeight: 600, show: !hidden, title: 'Call Ranya',
    icon: path.join(__dirname, 'build', 'icon.png'), backgroundColor: '#0e1420', autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, backgroundThrottling: false }
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.on('close', (e) => { if (!quitting) { e.preventDefault(); win.hide(); } });   // keep listening for calls in the tray
  win.on('focus', () => win.flashFrame(false));
}
function showWindow() { if (!win) return; if (win.isMinimized()) win.restore(); win.show(); win.focus(); }

function showBanner(mode) {
  hideBanner();
  const d = screen.getPrimaryDisplay().workArea;
  banner = new BrowserWindow({ width: 560, height: 54, x: d.x + Math.round((d.width - 560) / 2), y: d.y + 8, frame: false,
    alwaysOnTop: true, skipTaskbar: true, resizable: false, focusable: true, transparent: false, backgroundColor: '#c62828',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true } });
  banner.setAlwaysOnTop(true, 'screen-saver');
  banner.setContentProtection(true);   // the banner itself is never captured
  const who = (A.calls.s.peer || {}).name || '';
  banner.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(`<!doctype html><html><body style="margin:0;font:600 15px 'Segoe UI',sans-serif;color:#fff;background:#c62828;display:flex;align-items:center;justify-content:space-between;padding:0 14px;height:54px;user-select:none">
    <span>🔴 ${mode === 'control' ? tr('rs_banner_control') : tr('rs_banner_view')}: ${who.replace(/[<>&]/g, '')}</span>
    <button onclick="window.uor.invoke('support:stop')" style="font:700 14px 'Segoe UI';background:#fff;color:#c62828;border:0;border-radius:8px;padding:8px 16px;cursor:pointer">${tr('rs_stop')}</button></body></html>`));
}
A.bannerWin = () => banner;
function hideBanner() { if (banner && !banner.isDestroyed()) banner.destroy(); banner = null; }

/* ---------------- LAN server (TCP 45455) ---------------- */
function startServer() {
  const srv = net.createServer(async (sock) => {
    sock.setNoDelay(true);
    const ip = (sock.remoteAddress || '').replace(/^::ffff:/, '');
    const ch = A.messenger.channel(sock);
    try {
      const h = await ch.readHello(); if (!h) return ch.close();
      if (!A.store.allowed(h.remote.code)) return ch.refuse('unavailable');
      if (h.type === 'msg') { ch.accept('msg', h.eph); await A.messenger.receive(ch, h.remote, ip); return ch.close(); }
      if (h.type === 'call') return A.calls.handleIncoming(ch, h, ip);
      ch.close();
    } catch (e) { ch.close(); }
  });
  srv.on('error', () => setTimeout(startServer, 3000));
  srv.listen(SIGNAL_PORT, '0.0.0.0');
}

/* ---------------- IPC ---------------- */
function ipc() {
  const h = (name, fn) => ipcMain.handle(name, (e, ...a) => fn(...a));
  h('state', () => snapshot());
  h('save-settings', (patch) => {
    const before = Object.assign({}, A.store.settings);
    A.store.saveSettings(patch);
    if (patch.code && patch.code !== before.code) A.cloud.restart();
    if ('internet' in patch) patch.internet ? A.cloud.start() : A.cloud.stop();
    if ('autoStart' in patch) app.setLoginItemSettings({ openAtLogin: !!patch.autoStart, args: ['--hidden'] });
    if (patch.registered && !before.registered) { A.cloud.start(); }
    A.lan.announce(); A.changed(); return snapshot();
  });
  h('new-code', () => Store.newCode());
  h('contact:add', (c) => { A.store.upsertContact(c); A.changed(); });
  h('contact:patch', (code, p) => { A.store.patchContact(code, p); A.changed(); });
  h('contact:remove', (code) => { A.store.removeContact(code); A.changed(); });
  h('resolve', async (q) => {
    const p = await A.lan.resolve(q, 2000); if (p) return p;
    const u = await A.cloud.lookup(q); return u ? { code: u.code, name: u.name, ip: '', phone: '' } : null;
  });
  h('dial', (peer, opts) => A.calls.dial(peer, opts));
  h('accept', (video) => A.calls.accept(video));
  h('decline', () => A.calls.decline());
  h('hangup', () => A.calls.hangup());
  h('support:allow', (mode) => A.calls.setSupportMode(mode));
  h('support:stop', () => A.calls.setSupportMode('off'));
  h('rtc:out', (o) => A.calls.send(Object.assign({ c: 'rtc' }, o)));
  h('rtc:cam', (on) => A.calls.send({ c: 'cam', on }));
  h('rtc:state', (st) => { if (st === 'connected') { A.calls.set({ message: 'connected' }); } else if (st === 'failed') A.calls.end('lost', true); });
  h('input', (e) => { const s = A.calls.s; if (s.support.role === 'target' && s.support.mode === 'control' && s.phase === 'active') A.input.handle(e); });
  h('chat:messages', (peer) => A.store.messages.filter((m) => m.peer === peer));
  h('chat:open', (peer) => { A.openChat = peer; if (peer) { A.store.markRead(peer); A.changed(); } });
  h('chat:delete', (peer) => { A.store.deleteChat(peer); A.changed(); });
  h('msg:text', (peer, name, text) => A.messenger.sendControl(peer, name, 'text', text));
  h('msg:control', (peer, name, kind, body) => A.messenger.sendControl(peer, name, kind, body));
  h('msg:file', async (peer, name) => {
    const r = await dialog.showOpenDialog(win, { properties: ['openFile', 'multiSelections'] });
    if (r.canceled) return null;
    for (const f of r.filePaths) { const err = A.messenger.sendFile(peer, name, f); if (err) return err; }
    return null;
  });
  h('msg:drop', (peer, name, files) => { for (const f of files) A.messenger.sendFile(peer, name, f); });
  h('msg:open', (p) => shell.openPath(p));
  h('msg:show', (p) => shell.showItemInFolder(p));
  h('msg:retry', (id) => A.messenger.retryNow(id));
  h('history:clear', () => { A.store.clearHistory(); A.changed(); });
  h('qr', (text) => QRCode.toDataURL(text, { margin: 1, width: 360, errorCorrectionLevel: 'M' }));
  h('my-card', () => {
    const s = A.store.settings;
    return 'UORCALL:C:' + Buffer.from(JSON.stringify({ c: s.code, n: s.name, p: s.phone, k: A.identity.publicB64, ip: A.lan.myIp })).toString('base64url');
  });
  h('rollcall:start', (title) => {
    const team = A.store.team(); if (!team.length) return null;
    const rc = { id: require('crypto').randomUUID().slice(0, 12), title, time: Date.now(), members: team.map((c) => c.code) };
    team.forEach((c) => A.messenger.sendControl(c.code, c.name, 'rollcall', JSON.stringify({ rid: rc.id, title })));
    A.store.saveRollcalls([rc].concat(A.store.rollcalls)); A.changed(); return rc;
  });
  h('rollcall:delete', (id) => { A.store.saveRollcalls(A.store.rollcalls.filter((r) => r.id !== id)); A.changed(); });
  h('checkin', (status, note, rid, to) => {
    const targets = to ? [A.store.contact(to) || { code: to, name: to }] : A.store.team();
    const body = JSON.stringify({ status, note: note || '', rid: rid || '' });
    targets.forEach((c) => A.messenger.sendControl(c.code, c.name, 'checkin', body));
    return targets.length;
  });
  h('quit', () => { quitting = true; app.quit(); });
}

/* ---------------- start ---------------- */
app.whenReady().then(() => {
  const dir = app.getPath('userData');
  A.store = new Store(dir);
  A.store.onChange = () => A.changed();
  const idFile = path.join(dir, 'identity.json');
  let saved = null; try { saved = JSON.parse(fs.readFileSync(idFile, 'utf8')); } catch (e) {}
  A.identity = new Identity(saved);
  if (!saved) fs.writeFileSync(idFile, JSON.stringify(A.identity.save()));
  A.input = new Input(RES);
  A.lan = new Lan(A.store, { changed: () => A.changed(), isBusy: () => A.calls && A.calls.busy() });
  A.calls = new Calls(A);
  A.messenger = new Messenger(A);
  A.cloud = new Cloud(cfg, A.store, A.identity, (f, c, o, k, sec) => A.calls.onCloud(f, c, o, sec), () => A.messenger.fetchInbox());

  // Screen capture only after the local user allowed a support session (or started screen sharing).
  session.defaultSession.setDisplayMediaRequestHandler((req, cb) => {
    if (!A.allowScreen) return cb({});
    desktopCapturer.getSources({ types: ['screen'] }).then((s) => cb(s.length ? { video: s[0] } : {})).catch(() => cb({}));
  });
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(['media', 'notifications', 'display-capture'].includes(perm)));

  ipc();
  ipcMain.handle('screen:allow', () => { A.allowScreen = true; });
  createWindow(process.argv.includes('--hidden'));
  startServer();
  A.lan.start();
  A.messenger.start();
  A.cloud.start();
  powerSaveBlocker.start('prevent-app-suspension');     // never sleep while listening
  if (A.store.settings.autoStart) app.setLoginItemSettings({ openAtLogin: true, args: ['--hidden'] });

  tray = new Tray(nativeImage.createFromPath(path.join(__dirname, 'build', 'icon.png')).resize({ width: 16, height: 16 }));
  tray.setToolTip('Call Ranya');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open Call Ranya', click: showWindow },
    { type: 'separator' },
    { label: 'Quit (stop receiving calls)', click: () => { quitting = true; app.quit(); } }
  ]));
  tray.on('click', showWindow);
  if (process.env.UOR_TEST_SHOTS) require('./test/shots.js')(A, win);
  if (process.env.UOR_TEST_LAN) require('./test/lan.js')(A, win);
});
app.on('second-instance', showWindow);
app.on('before-quit', () => { quitting = true; A.input && A.input.stop(); hideBanner(); });
app.on('window-all-closed', (e) => { /* stay in tray */ });
