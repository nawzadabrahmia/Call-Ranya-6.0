'use strict';
/* LAN test: wait for a real encrypted message from the Android simulator, then screenshot the chat. */
const fs = require('fs');
module.exports = async function (A, win) {
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  A.store.saveSettings({ registered: true, name: 'Office PC', phone: '07501234567', code: '48271936' });
  A.changed(); win.setSize(1280, 800); win.show();
  console.log('PC READY');
  for (let i = 0; i < 120 && A.store.messages.filter((m) => m.peer === '11112222').length < 2; i++) await wait(250);
  const got = A.store.messages.filter((m) => m.peer === '11112222');
  console.log('PC RECEIVED', got.length, 'messages:', got.map((m) => m.kind + '=' + m.body).join(' | '));
  await wait(600);
  await win.webContents.executeJavaScript(`openChat('11112222')`);
  await wait(900);
  fs.writeFileSync(process.env.UOR_TEST_LAN, (await win.webContents.capturePage()).toPNG());
  process.exit(got.length === 2 ? 0 : 1);
};
