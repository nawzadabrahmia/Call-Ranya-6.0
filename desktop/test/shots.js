'use strict';
/* Screenshot test: seeds demo data, walks through every screen, saves PNGs. Not shipped in the app. */
const fs = require('fs');
const path = require('path');
module.exports = async function (A, win) {
  const out = process.env.UOR_TEST_SHOTS; fs.mkdirSync(out, { recursive: true });
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const js = (code) => win.webContents.executeJavaScript(code);
  const click = (sel) => js(`(()=>{const e=document.querySelector(${JSON.stringify(sel)}); if(e){e.click(); return true} return false})()`);
  const shot = async (name) => { await wait(700); const img = await win.webContents.capturePage(); fs.writeFileSync(path.join(out, name + '.png'), img.toPNG()); console.log('SHOT', name); };
  const errors = [];
  win.webContents.on('console-message', (e, level, msg) => { if (level >= 2) { errors.push(msg); console.log('RENDERER', msg); } });
  win.setSize(1280, 800); win.show();
  await new Promise((r) => win.webContents.once('did-finish-load', r)).catch(() => {});
  await wait(1500);
  await shot('01-onboarding');

  // register + demo data
  A.store.saveSettings({ registered: true, name: 'Nawzad (Office PC)', phone: '07501234567', visible: true, org: 'UoR English Dept', code: '48271936' });
  const now = Date.now();
  const people = [['20481357', 'Dr. Awat Karim', '07701112233', true, true], ['31574820', 'Shilan Ahmed', '07502223344', true, false],
    ['55120984', 'Hemin Omar', '', false, true], ['67823015', 'Rozh Salar', '07703334455', true, true], ['90215478', 'Lana Faraj', '', false, false]];
  people.forEach(([code, name, phone, team, verified]) => A.store.upsertContact({ code, name, phone, ip: '10.10.5.' + code.slice(-2), key: '', verified, team }));
  A.lan.visible.set('31574820', { code: '31574820', name: 'Shilan Ahmed', ip: '10.10.5.20', phone: '', lastSeen: now + 1e9, status: 0, visible: true });
  A.lan.visible.set('67823015', { code: '67823015', name: 'Rozh Salar', ip: '10.10.5.15', phone: '', lastSeen: now + 1e9, status: 1, visible: true });
  A.lan.visible.set('70114523', { code: '70114523', name: 'Library Desk', ip: '10.10.5.40', phone: '', lastSeen: now + 1e9, status: 0, visible: true });
  A.lan.myIp = '10.10.5.7';
  const msg = (peer, name, out, kind, body, mins, extra = {}) => A.store.addMessage(Object.assign({ id: 'd' + Math.random(), peer, peerName: name, out, kind, body, path: '', fileName: '', mime: '', size: 0, time: now - mins * 60000, status: out ? 1 : 3, read: true }, extra));
  msg('20481357', 'Dr. Awat Karim', false, 'text', 'Salam! The projector in Hall 3 is not showing my laptop screen 😕', 14);
  msg('20481357', 'Dr. Awat Karim', true, 'text', 'No problem — I will connect with Remote support and fix it.', 13);
  msg('20481357', 'Dr. Awat Karim', false, 'text', 'Thank you! I accepted the request.', 12);
  msg('20481357', 'Dr. Awat Karim', true, 'file', '', 10, { fileName: 'Exam-Schedule-Fall-2026.pdf', size: 482113, mime: 'application/pdf' });
  msg('20481357', 'Dr. Awat Karim', false, 'text', 'Received, perfect 👍', 9, { read: false });
  msg('31574820', 'Shilan Ahmed', false, 'text', 'سڵاو، کۆبوونەوەکە کاتژمێر ١١ە', 45);
  msg('55120984', 'Hemin Omar', false, 'checkin', JSON.stringify({ status: 'help', note: 'Building B, 2nd floor – door stuck', rid: 'rc1', lat: 36.21, lon: 44.88, acc: 12 }), 6, { read: false });
  msg('67823015', 'Rozh Salar', false, 'checkin', JSON.stringify({ status: 'safe', note: '', rid: 'rc1' }), 5);
  msg('20481357', 'Dr. Awat Karim', false, 'checkin', JSON.stringify({ status: 'safe', note: 'At home', rid: 'rc1' }), 4);
  A.store.saveRollcalls([{ id: 'rc1', title: 'Are you safe? (earthquake drill)', time: now - 8 * 60000, members: ['20481357', '55120984', '67823015'] }]);
  [['in', '20481357', 'Dr. Awat Karim', 20, 312], ['out', '31574820', 'Shilan Ahmed', 90, 95], ['missed', '67823015', 'Rozh Salar', 200, 0], ['declined', '90215478', 'Lana Faraj', 1500, 0]]
    .forEach(([type, code, name, mins, d]) => A.store.addHistory({ type, code, name, ip: '', time: now - mins * 60000, duration: d }));
  A.changed(); await wait(800);
  await js(`openChat('20481357')`); await shot('02-chats');
  await click('[data-act=nav][data-v=contacts]'); await shot('03-contacts');
  await click('[data-act=nav][data-v=calls]'); await shot('04-calls');
  await click('[data-act=nav][data-v=team]'); await shot('05-team');
  await click('[data-act=openRc]'); await shot('06-rollcall-dashboard'); await js('closeModal()');
  await click('[data-act=nav][data-v=support]'); await shot('07-remote-support');
  await click('[data-act=nav][data-v=settings]'); await wait(600); await shot('08-settings');

  // incoming remote-support request on this PC (consent screen)
  const setCall = (patch) => { A.calls.s = Object.assign({ phase: 'idle', peer: null, incoming: false, startedAt: 0, message: '', security: 0, internet: false, wantVideo: false, support: { req: false, role: '', mode: '' } }, patch); A.callChanged(); };
  setCall({ phase: 'incoming', peer: { code: '20481357', name: 'Dr. Awat Karim', ip: '10.10.5.57' }, incoming: true, message: 'incoming', security: 1, support: { req: true, role: 'target', mode: '' } });
  await shot('09-incoming-support-request');
  setCall({ phase: 'active', peer: { code: '20481357', name: 'Dr. Awat Karim', ip: '10.10.5.57' }, incoming: true, startedAt: Date.now() - 42000, message: 'connected', security: 1, support: { req: true, role: 'target', mode: '' } });
  await shot('10-choose-view-or-control');
  // this PC is being helped: control allowed → red always-on-top banner with Stop
  setCall({ phase: 'active', peer: { code: '20481357', name: 'Dr. Awat Karim', ip: '10.10.5.57' }, incoming: true, startedAt: Date.now() - 95000, message: 'connected', security: 1, support: { req: true, role: 'target', mode: 'control' } });
  A.supportChanged('control'); await wait(1500);
  await shot('15-being-helped-control');
  const bw = A.bannerWin(); if (bw) { await wait(400); fs.writeFileSync(path.join(out, '16-red-banner.png'), (await bw.webContents.capturePage()).toPNG()); console.log('SHOT 16-red-banner'); }
  A.supportChanged('off');
  // this PC helps someone: control view with toolbar
  setCall({ phase: 'active', peer: { code: '31574820', name: 'Shilan Ahmed (Android)', ip: '10.10.5.20' }, incoming: false, startedAt: Date.now() - 30000, message: 'connected', security: 1, support: { req: true, role: 'helper', mode: 'control' } });
  await shot('17-helping-control-view');
  setCall({ phase: 'incoming', peer: { code: '31574820', name: 'Shilan Ahmed', ip: '10.10.5.20' }, incoming: true, message: 'incoming', wantVideo: true, internet: true });
  await shot('11-incoming-video-call');
  setCall({ phase: 'idle' }); await wait(500);

  // Kurdish (RTL) + dark theme
  A.store.saveSettings({ lang: 'ckb', theme: 'dark' }); A.changed(); await wait(500);
  await click('[data-act=nav][data-v=team]'); await shot('12-kurdish-dark-team');
  await js(`openChat('20481357')`); await shot('13-kurdish-dark-chat');
  A.store.saveSettings({ lang: 'ar' }); A.changed(); await wait(400);
  await click('[data-act=nav][data-v=support]'); await shot('14-arabic-dark-support');
  fs.writeFileSync(path.join(out, 'errors.txt'), errors.join('\n'));
  console.log('DONE errors=' + errors.length);
  setTimeout(() => process.exit(0), 300);
};
