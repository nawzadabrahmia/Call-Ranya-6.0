'use strict';
/* UoR Call for Windows – renderer (UI + WebRTC). Talks to the main process through window.uor. */
const api = window.uor;
let S = null;                       // snapshot from main
let view = 'chats', chatPeer = null, chatMsgs = [], contactQ = '', dialNum = '', sosPeer = null;
const $ = (s, r = document) => r.querySelector(s);
const t = (k) => I18N.t(S ? S.settings.lang : 'en', k);
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = (c) => '\u2066' + (c && c.length === 8 ? c.slice(0, 4) + ' ' + c.slice(4) : (c || '')) + '\u2069';
const COLORS = ['#1A73E8', '#0F9D58', '#E8710A', '#9334E6', '#D93025', '#12A4AF', '#B5179E', '#5F6368'];
const color = (k) => { let h = 0; for (const ch of String(k)) h = (h * 31 + ch.charCodeAt(0)) | 0; return COLORS[Math.abs(h) % COLORS.length]; };
const initials = (n) => (String(n || '?').trim().split(/\s+/).map((w) => w.replace(/^[^\p{L}\p{N}]+/u, '')).filter(Boolean).slice(0, 2).map((w) => [...w][0]).join('') || '?').toUpperCase();
const ago = (ms) => { const s = (Date.now() - ms) / 1000; if (s < 60) return t('just_now'); if (s < 3600) return Math.floor(s / 60) + ' ' + t('min'); if (s < 86400) return Math.floor(s / 3600) + ' ' + t('hr'); return new Date(ms).toLocaleDateString(); };
const clock = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const dur = (sec) => { sec = Math.max(0, sec | 0); return (sec >= 3600 ? Math.floor(sec / 3600) + ':' : '') + String(Math.floor(sec / 60) % 60).padStart(2, '0') + ':' + String(sec % 60).padStart(2, '0'); };
function avatar(name, key, online, status) {
  const dc = online == null ? '' : `<span class="dot" style="background:${!online ? '#888' : status === 1 ? 'var(--red)' : status === 2 ? 'var(--gold)' : 'var(--green)'}"></span>`;
  return `<div class="avatar" style="background:${color(key)}">${esc(initials(name))}${dc}</div>`;
}
function toast(msg) { const d = document.createElement('div'); d.className = 'toast'; d.textContent = msg; document.body.appendChild(d); setTimeout(() => d.remove(), 2600); }
function modal(html) { $('#modal').innerHTML = `<div class="mbg" data-act="closeModalBg"><div class="mbox">${html}</div></div>`; }
function closeModal() { $('#modal').innerHTML = ''; }
function sw(on, act, extra = '') { return `<div class="switch ${on ? 'on' : ''}" data-act="${act}" ${extra}></div>`; }
function toggleRow(title, sub, on, act) { return `<div class="toggle"><div class="grow"><div class="name">${esc(title)}</div><div class="muted">${esc(sub)}</div></div>${sw(on, act)}</div>`; }

/* ======================= rendering ======================= */
function render() {
  if (!S) return;
  document.documentElement.dir = S.settings.lang === 'en' ? 'ltr' : 'rtl';
  const th = S.settings.theme === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : S.settings.theme;
  document.documentElement.dataset.theme = th;
  if (!S.settings.registered) { $('#app').innerHTML = onboarding(); return; }
  const unread = S.conversations.reduce((a, c) => a + c.unread, 0);
  const nav = [['chats', '💬'], ['contacts', '👥'], ['calls', '📞'], ['team', '🛡'], ['support', '🛟'], ['settings', '⚙️']];
  const s = S.settings;
  const keepScroll = $('.page') ? $('.page').scrollTop : 0;
  const msgsScroll = $('.msgs') ? [$('.msgs').scrollTop, $('.msgs').scrollHeight - $('.msgs').clientHeight - $('.msgs').scrollTop] : null;
  const draft = $('#composer') ? $('#composer').value : '';
  const focusId = document.activeElement && document.activeElement.id;
  $('#app').innerHTML = `<div class="layout">
    <aside class="side">
      <div class="me"><div class="lbl">${esc(t('my_code')).toUpperCase()}</div><div class="code">${fmt(s.code)}</div>
        <div class="sub">${esc(s.name)}${s.org ? ' • ' + esc(s.org) : ''}</div></div>
      <nav class="nav">${nav.map(([k, i]) => `<button class="${view === k ? 'on' : ''}" data-act="nav" data-v="${k}"><span>${i}</span>${esc(t(k === 'support' ? 'remote_support' : k))}${k === 'chats' && unread ? `<span class="badge">${unread}</span>` : ''}</button>`).join('')}</nav>
      <div class="foot">
        <div><span class="dot" style="background:${s.dnd ? 'var(--gold)' : 'var(--green)'}"></span> ${esc(s.dnd ? t('dnd') : t('ready'))}</div>
        <div>📶 ${esc(S.myIp || t('no_wifi'))} • ${S.peers.length} ${esc(t('online'))}</div>
        <div>${s.visible ? '👁 ' + esc(t('visible')) : '🕶 ' + esc(t('hidden'))}${S.cloud === 2 ? ' • 🌐' : S.cloud === 1 ? ' • 🌐…' : ''}</div>
        ${S.conflict ? `<div class="warn">⚠ ${esc(t('code_conflict'))}</div>` : ''}
      </div>
    </aside>
    <main class="main">${sosPeer ? sosBar() : ''}${page()}</main></div>`;
  if ($('.page')) $('.page').scrollTop = keepScroll;
  const m = $('.msgs');
  if (m && msgsScroll) m.scrollTop = msgsScroll[1] < 40 ? m.scrollHeight : msgsScroll[0];
  else if (m) m.scrollTop = m.scrollHeight;
  if ($('#composer')) { $('#composer').value = draft; if (focusId === 'composer') $('#composer').focus(); }
  if (focusId === 'contactQ' && $('#contactQ')) { const q = $('#contactQ'); q.focus(); q.setSelectionRange(q.value.length, q.value.length); }
}
function sosBar() {
  const c = S.contacts.find((x) => x.code === sosPeer) || S.conversations.find((x) => x.peer === sosPeer);
  return `<div class="card sos" style="margin:10px;border-radius:12px" data-act="openChat" data-p="${sosPeer}">🆘 <b>${esc(c ? c.name : sosPeer)}</b> — ${esc(t('need_help'))} <button class="ghost" data-act="dismissSos">✕</button></div>`;
}
function page() {
  switch (view) {
    case 'chats': return chatsPage();
    case 'contacts': return `<div class="page">${contactsPage()}</div>`;
    case 'calls': return `<div class="page">${callsPage()}</div>`;
    case 'team': return `<div class="page">${teamPage()}</div>`;
    case 'support': return `<div class="page">${supportPage()}</div>`;
    default: return `<div class="page">${settingsPage()}</div>`;
  }
}

function onboarding() {
  const s = S.settings;
  return `<div class="page"><div class="onb card">
    <div style="text-align:center"><div class="avatar" style="width:72px;height:72px;margin:0 auto;background:var(--brand);font-size:34px">📞</div>
    <h2>${esc(t('app_name'))}</h2><div class="muted">${esc(t('tagline'))}</div></div>
    <div style="text-align:center;margin:14px 0">${langChips()}</div>
    <label>${esc(t('full_name'))}</label><input id="obName" value="${esc(s.name)}" maxlength="40">
    <label style="display:block;margin-top:8px">${esc(t('phone'))}</label><input id="obPhone" value="${esc(s.phone)}" maxlength="16">
    <div class="card" style="margin-top:12px"><b>${esc(t('your_code'))}</b><div style="font-size:30px;font-weight:700;letter-spacing:4px;color:var(--primary)">${fmt(s.code)}</div>
      <div class="muted">${esc(t('code_explain'))}</div></div>
    ${toggleRow(t('show_available'), t('show_available_sub'), obVisible, 'obVisible')}
    <button class="primary" style="width:100%;height:46px;margin-top:10px" data-act="register">${esc(t('start'))}</button>
    <div class="muted" style="text-align:center;margin-top:10px">🔒 ${esc(t('privacy_note'))}</div></div></div>`;
}
let obVisible = false;
function langChips() {
  return [['en', 'English'], ['ckb', 'کوردی'], ['ar', 'العربية']].map(([c, l]) => `<span class="chip ${S.settings.lang === c ? 'on' : ''}" data-act="lang" data-v="${c}">${l}</span>`).join('');
}

/* ---- chats ---- */
function preview(m) {
  if (m.kind === 'text') return m.body;
  if (m.kind === 'image') return '📷 ' + t('photo');
  if (m.kind === 'video') return '🎬 ' + t('video');
  if (m.kind === 'rollcall') return '📋 ' + t('rollcall');
  if (m.kind === 'checkin') { try { return JSON.parse(m.body).status === 'help' ? '🆘 ' + t('need_help') : '✅ ' + t('im_safe'); } catch (e) { return ''; } }
  if (m.kind === 'room') return '🎙 ' + t('room_invite');
  return '📎 ' + m.fileName;
}
function chatsPage() {
  const convs = S.conversations;
  const list = convs.length ? convs.map((c) => `<div class="item" data-act="openChat" data-p="${c.peer}" style="${c.peer === chatPeer ? 'background:var(--bg2)' : ''}">
      ${avatar(c.name, c.peer, !!S.online[c.peer], 0)}<div class="grow"><div class="name">${esc(c.name)}</div>
      <div class="muted" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${c.last.out ? '✓ ' : ''}${esc(preview(c.last))}</div></div>
      <div style="text-align:end"><div class="muted">${ago(c.last.time)}</div>${c.unread ? `<span class="chip on" style="padding:0 8px;background:var(--green);border:0">${c.unread}</span>` : ''}</div></div>`).join('')
    : `<div class="muted" style="padding:20px;text-align:center">${esc(t('no_chats'))}</div>`;
  return `<div class="chat"><div class="convs list"><h2>${esc(t('chats'))}</h2>${list}</div><div class="pane">${chatPane()}</div></div>`;
}
function chatPane() {
  if (!chatPeer) return `<div class="muted" style="margin:auto;text-align:center">💬<br>${esc(t('select_chat'))}</div>`;
  const c = S.contacts.find((x) => x.code === chatPeer);
  const conv = S.conversations.find((x) => x.peer === chatPeer);
  const name = c ? c.name : conv ? conv.name : chatPeer;
  const on = !!S.online[chatPeer];
  return `<div class="top">${avatar(name, chatPeer, on, 0)}<div class="grow"><div class="name">${esc(name)} ${c && c.verified ? '✔️' : ''}</div>
      <div class="muted">${fmt(chatPeer)} • ${esc(on ? t('online') : t('offline'))}</div></div>
      <button class="icon" title="${esc(t('call'))}" data-act="call" data-p="${chatPeer}">📞</button>
      <button class="icon" title="${esc(t('video_call'))}" data-act="video" data-p="${chatPeer}">🎥</button>
      <button class="icon" title="${esc(t('remote_support'))}" data-act="support" data-p="${chatPeer}">🛟</button>
      ${!c ? `<button class="ghost" data-act="saveContact" data-p="${chatPeer}">${esc(t('save_contact'))}</button>` : ''}
      <button class="icon" title="${esc(t('delete_chat'))}" data-act="deleteChat">🗑</button></div>
    <div class="e2e">🔒 ${esc(t('e2e_note'))}</div>
    <div class="msgs" id="msgs">${chatMsgs.map(bubble).join('')}</div>
    <div class="composer"><button class="icon" data-act="attach" title="${esc(t('attach'))}">📎</button>
      <textarea id="composer" placeholder="${esc(t('type_message'))}"></textarea><button class="primary" data-act="send">${esc(t('send'))}</button></div>`;
}
function bubble(m) {
  const url = m.path ? 'file:///' + m.path.replace(/\\/g, '/').replace(/^\/+/, '') : '';
  let inner = '';
  if (m.kind === 'image' && url) inner = `<img src="${esc(url)}" data-act="openFile" data-path="${esc(m.path)}">`;
  else if (m.kind === 'video' && url) inner = `<video src="${esc(url)}" controls preload="metadata"></video>`;
  else if (m.kind === 'text') inner = esc(m.body);
  else if (m.kind === 'rollcall') { let o = {}; try { o = JSON.parse(m.body); } catch (e) {}
    inner = `<b>📋 ${esc(t('rollcall'))}</b><br>${esc(o.title || t('are_you_safe'))}` + (!m.out ? `<div class="row" style="margin-top:6px"><button class="green" data-act="answerRc" data-p="${m.peer}" data-rid="${esc(o.rid)}" data-s="safe">✅ ${esc(t('im_safe'))}</button><button class="red" data-act="answerRc" data-p="${m.peer}" data-rid="${esc(o.rid)}" data-s="help">🆘</button></div>` : ''); }
  else if (m.kind === 'checkin') { let o = {}; try { o = JSON.parse(m.body); } catch (e) {}
    inner = `<b style="color:${o.status === 'help' ? 'var(--red)' : 'var(--green)'}">${o.status === 'help' ? '🆘 ' + esc(t('need_help')) : '✅ ' + esc(t('im_safe'))}</b>${o.note ? '<br>' + esc(o.note) : ''}${o.lat != null ? `<br><a href="#" data-act="map" data-lat="${o.lat}" data-lon="${o.lon}">📍 ${esc(t('location'))} (±${o.acc | 0} m)</a>` : ''}`; }
  else if (m.kind === 'room') inner = `🎙 ${esc(t('room_invite'))}<br><span class="muted">${esc(t('rooms_android_only'))}</span>`;
  else inner = `<div class="row" data-act="openFile" data-path="${esc(m.path)}" style="cursor:pointer">📄<div><b>${esc(m.fileName)}</b><div class="muted">${(m.size / 1048576).toFixed(m.size > 1048576 ? 1 : 2)} MB</div></div></div>`;
  if (m.body && (m.kind !== 'text' && ['image', 'video', 'audio', 'file'].includes(m.kind))) inner += `<div>${esc(m.body)}</div>`;
  const st = m.out ? (m.status === 1 ? ' ✓✓' : m.status === 0 ? ' ⏳' : ` <a href="#" data-act="retry" data-id="${m.id}" style="color:var(--red)">⚠ ${esc(t('tap_retry'))}</a>`) : '';
  return `<div class="b ${m.out ? 'me' : ''}" dir="auto">${inner}<div class="meta">${clock(m.time)}${st}</div></div>`;
}

/* ---- contacts ---- */
function contactsPage() {
  const q = contactQ.trim().toLowerCase();
  const match = (n, c, p) => !q || String(n).toLowerCase().includes(q) || c.includes(q) || (p && p.includes(q));
  const mine = S.contacts.filter((c) => !c.blocked && match(c.name, c.code, c.phone));
  const codes = new Set(S.contacts.map((c) => c.code));
  const avail = S.peers.filter((p) => !codes.has(p.code) && match(p.name, p.code, p.phone));
  const row = (name, code, phone, online, status, verified, team, saved) => `<div class="item">
      ${avatar(name, code, online, status)}<div class="grow" data-act="openChat" data-p="${code}"><div class="name">${esc(name)} ${verified ? '✔️' : ''} ${team ? '🛡' : ''}</div>
      <div class="muted">${fmt(code)}${phone ? ' • ' + esc(phone) : ''}${online ? ' • ' + esc(status === 1 ? t('in_a_call') : status === 2 ? t('dnd') : t('online')) : ''}</div></div>
      <button class="icon" data-act="openChat" data-p="${code}" title="${esc(t('message'))}">💬</button>
      <button class="icon" data-act="call" data-p="${code}" title="${esc(t('call'))}">📞</button>
      <button class="icon" data-act="video" data-p="${code}" title="${esc(t('video_call'))}">🎥</button>
      <button class="icon" data-act="support" data-p="${code}" title="${esc(t('remote_support'))}">🛟</button>
      <button class="icon" data-act="contactMenu" data-p="${code}" data-saved="${saved ? 1 : 0}">⋮</button></div>`;
  return `<div class="row"><h2 class="grow">${esc(t('contacts'))}</h2><button class="primary" data-act="addContact">＋ ${esc(t('add_contact'))}</button></div>
    <input id="contactQ" placeholder="${esc(t('search_hint'))}" value="${esc(contactQ)}">
    <h3>${esc(t('my_contacts'))} (${mine.length})</h3><div class="list">${mine.map((c) => { const l = S.online[c.code]; return row(c.name, c.code, c.phone, !!l, l ? l.status : 0, c.verified, c.team, true); }).join('') || `<div class="muted">${esc(t('no_contacts_pc'))}</div>`}</div>
    <h3>${esc(t('available_now'))} (${avail.length})</h3><div class="list">${avail.map((p) => row(p.name, p.code, p.phone, true, p.status, false, false, false)).join('') || `<div class="muted">${esc(t('no_available'))}</div>`}</div>`;
}

/* ---- calls ---- */
function callsPage() {
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '0', '⌫'];
  const hist = S.history.map((h) => {
    const c = S.contacts.find((x) => x.code === h.code);
    const ic = { out: '↗️', in: '↙️', declined: '⛔', missed: '❌' }[h.type] || '❌';
    return `<div class="item" data-act="call" data-p="${h.code}">${ic}<div class="grow"><div class="name" style="${h.type === 'missed' ? 'color:var(--red)' : ''}">${esc(c ? c.name : h.name)}</div>
      <div class="muted">${esc(t(h.type === 'in' ? 'incoming_l' : h.type === 'out' ? 'outgoing' : h.type))} • ${fmt(h.code)}${h.duration ? ' • ' + dur(h.duration) : ''}</div></div><div class="muted">${ago(h.time)}</div></div>`;
  }).join('');
  return `<div style="display:grid;grid-template-columns:320px 1fr;gap:24px">
    <div class="card" style="text-align:center"><h3>${esc(t('keypad'))}</h3>
      <input id="dial" value="${esc(dialNum)}" placeholder="${esc(t('keypad_hint'))}" style="font-size:24px;text-align:center;letter-spacing:2px">
      <div class="keypad">${keys.map((k) => `<button data-act="key" data-k="${k}">${k}</button>`).join('')}</div>
      <div class="row" style="justify-content:center"><button class="green" data-act="dialGo" data-m="call">📞 ${esc(t('call'))}</button>
      <button data-act="dialGo" data-m="video">🎥</button><button data-act="dialGo" data-m="chat">💬</button><button data-act="dialGo" data-m="support">🛟</button></div></div>
    <div><div class="row"><h2 class="grow">${esc(t('call_history'))}</h2>${S.history.length ? `<button class="ghost" data-act="clearHistory">${esc(t('clear'))}</button>` : ''}</div>
      <div class="list">${hist || `<div class="muted">${esc(t('no_calls'))}</div>`}</div></div></div>`;
}

/* ---- team ---- */
function checkinOf(code) {
  const l = S.checkins.filter((m) => m.peer === code); return l.length ? l[l.length - 1] : null;
}
function teamPage() {
  const team = S.contacts.filter((c) => c.team && !c.blocked);
  const rcs = S.rollcalls.slice(0, 6).map((rc) => {
    const resp = {}; S.checkins.forEach((m) => { try { if (JSON.parse(m.body).rid === rc.id) resp[m.peer] = m; } catch (e) {} });
    const st = (m) => { try { return JSON.parse(m.body).status; } catch (e) { return ''; } };
    const safe = Object.values(resp).filter((m) => st(m) === 'safe').length, help = Object.values(resp).filter((m) => st(m) === 'help').length;
    return `<div class="card row" data-act="openRc" data-id="${rc.id}" style="cursor:pointer"><div class="grow"><b>${esc(rc.title)}</b><div class="muted">${ago(rc.time)}</div></div>
      <b style="${help ? 'color:var(--red)' : ''}">✅ ${safe} &nbsp; 🆘 ${help} &nbsp; ⏳ ${rc.members.length - Object.keys(resp).length}</b></div>`;
  }).join('');
  const members = team.map((c) => {
    const m = checkinOf(c.code); let o = {}; if (m) { try { o = JSON.parse(m.body); } catch (e) {} }
    return `<div class="item">${avatar(c.name, c.code, !!S.online[c.code], 0)}<div class="grow" data-act="openChat" data-p="${c.code}"><div class="name">${esc(c.name)}</div>
      <div class="muted" style="${o.status === 'help' ? 'color:var(--red)' : ''}">${m ? (o.status === 'help' ? '🆘 ' + esc(t('need_help')) : '✅ ' + esc(t('im_safe'))) + ' • ' + ago(m.time) + (o.note ? ' • ' + esc(o.note) : '') : esc(t('no_status'))}</div></div>
      ${o.lat != null ? `<button class="icon" data-act="map" data-lat="${o.lat}" data-lon="${o.lon}">📍</button>` : ''}<button class="icon" data-act="call" data-p="${c.code}">📞</button></div>`;
  }).join('');
  return `<h2>🛡 ${esc(t('team'))}</h2>
    <div class="card"><b>${esc(t('safety_check'))}</b><div class="muted">${esc(t('safety_explain').replace('%d', team.length))}</div>
      <input id="note" placeholder="${esc(t('note_optional'))}" style="margin:10px 0">
      <div class="row"><button class="green grow" style="height:46px" data-act="checkin" data-s="safe">✅ ${esc(t('im_safe'))}</button>
      <button class="red grow" style="height:46px" data-act="checkin" data-s="help">🆘 ${esc(t('need_help'))}</button></div></div>
    <div class="row"><h3 class="grow">${esc(t('rollcalls'))}</h3><button class="primary" data-act="startRc">📣 ${esc(t('start_rollcall'))}</button></div>
    ${rcs || `<div class="muted">${esc(t('rollcall_explain'))}</div>`}
    <h3>${esc(t('my_team'))} (${team.length})</h3><div class="list">${members || `<div class="muted">${esc(t('team_how'))}</div>`}</div>`;
}

/* ---- remote support ---- */
function supportPage() {
  return `<h2>🛟 ${esc(t('remote_support'))}</h2>
    <div class="card"><div class="muted" style="font-size:14px;line-height:1.6">${esc(t('rs_explain'))}</div>
      <div class="row" style="margin-top:12px"><input id="rsCode" placeholder="${esc(t('code_or_phone'))}" class="grow"><button class="primary" data-act="rsGo">🛟 ${esc(t('rs_request'))}</button></div></div>
    <div class="card"><b>🔐 ${esc(t('rs_rules_title'))}</b><ul class="muted" style="line-height:1.8;margin:6px 0 0">
      <li>${esc(t('rs_rule1'))}</li><li>${esc(t('rs_rule2'))}</li><li>${esc(t('rs_rule3'))}</li><li>${esc(t('rs_rule4'))}</li></ul></div>
    <h3>${esc(t('my_contacts'))}</h3><div class="list">${S.contacts.filter((c) => !c.blocked).map((c) => `<div class="item" data-act="support" data-p="${c.code}">${avatar(c.name, c.code, !!S.online[c.code], 0)}
      <div class="grow"><div class="name">${esc(c.name)}</div><div class="muted">${fmt(c.code)}</div></div><button class="primary">🛟 ${esc(t('rs_help_this'))}</button></div>`).join('')}</div>`;
}

/* ---- settings ---- */
function settingsPage() {
  const s = S.settings;
  const chip = (key, val, label) => `<span class="chip ${s[key] === val ? 'on' : ''}" data-act="set" data-k="${key}" data-v="${val}">${esc(label)}</span>`;
  return `<h2>⚙️ ${esc(t('settings'))}</h2>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px">
    <div>
      <div class="card"><h3 style="margin-top:0">${esc(t('profile'))}</h3>
        <label>${esc(t('full_name'))}</label><input id="stName" value="${esc(s.name)}" maxlength="40">
        <label style="display:block;margin-top:8px">${esc(t('phone'))}</label><input id="stPhone" value="${esc(s.phone)}" maxlength="16">
        <div class="row" style="margin-top:10px"><button class="ghost" data-act="newCode">${esc(t('new_code'))}</button><span class="grow"></span><button class="primary" data-act="saveProfile">${esc(t('save'))}</button></div>
        <div class="muted">${esc(t('safety_number'))}: ${esc(S.fingerprint)}</div></div>
      <div class="card"><h3 style="margin-top:0">${esc(t('privacy'))}</h3>
        ${toggleRow(t('show_available'), t('show_available_sub'), s.visible, 'tg-visible')}
        ${toggleRow(t('share_phone'), t('share_phone_sub'), s.sharePhone, 'tg-sharePhone')}
        <div class="name" style="margin-top:6px">${esc(t('who_can_call'))}</div>${chip('whoCanCall', 'everyone', t('everyone'))}${chip('whoCanCall', 'contacts', t('contacts_only'))}
        ${S.contacts.filter((c) => c.blocked).map((b) => `<div class="row"><span class="grow">🚫 ${esc(b.name)} • ${fmt(b.code)}</span><button class="ghost" data-act="unblock" data-p="${b.code}">${esc(t('unblock'))}</button></div>`).join('')}</div>
      <div class="card"><h3 style="margin-top:0">${esc(t('calls'))}</h3>
        ${toggleRow(t('dnd'), t('dnd_sub'), s.dnd, 'tg-dnd')}
        ${toggleRow(t('auto_start'), t('auto_start_pc'), s.autoStart, 'tg-autoStart')}</div>
    </div><div>
      <div class="card" style="text-align:center"><h3 style="margin-top:0">${esc(t('my_qr'))}</h3><img id="myQr" style="width:220px;height:220px;background:#fff;border-radius:12px">
        <div class="muted">${esc(t('qr_pc_explain'))}</div></div>
      <div class="card"><h3 style="margin-top:0">${esc(t('appearance'))}</h3><div class="name">${esc(t('language'))}</div>${langChips()}
        <div class="name" style="margin-top:8px">${esc(t('theme'))}</div>${chip('theme', 'system', t('theme_system'))}${chip('theme', 'light', t('theme_light'))}${chip('theme', 'dark', t('theme_dark'))}</div>
      <div class="card"><h3 style="margin-top:0">${esc(t('internet'))}</h3>
        ${!S.cloudConfigured ? `<div class="muted">${esc(t('internet_not_configured'))}</div>` :
          toggleRow(t('internet_calls'), t('internet_calls_sub'), s.internet, 'tg-internet') + `<div class="muted">${esc(t('internet_status'))}: ${S.cloud === 2 ? '🟢 ' + esc(t('online')) : S.cloud === 1 ? '🟡 ' + esc(t('connecting')) : '⚪ ' + esc(t('offline'))}</div>`}</div>
      <div class="card"><h3 style="margin-top:0">${esc(t('about'))}</h3><div class="muted">${esc(t('about_pc'))}</div>
        <button style="margin-top:10px" data-act="quit">${esc(t('quit_app'))}</button></div>
    </div></div>`;
}

/* ======================= actions ======================= */
const peerOf = (code) => {
  const on = S.online[code]; if (on) return { code, name: on.name, ip: on.ip, phone: on.phone };
  const c = S.contacts.find((x) => x.code === code);
  const conv = S.conversations.find((x) => x.peer === code);
  return { code, name: c ? c.name : conv ? conv.name : code, ip: c ? c.ip : '', phone: c ? c.phone : '' };
};
async function openChat(p) {
  chatPeer = p; view = 'chats';
  await api.invoke('chat:open', p);
  chatMsgs = await api.invoke('chat:messages', p);
  render();
}
async function reloadChat() { if (chatPeer && view === 'chats') { chatMsgs = await api.invoke('chat:messages', chatPeer); } }
async function resolveInput(v) {
  v = String(v || '').replace(/\s/g, '');
  if (/^\d{8}$/.test(v)) return peerOf(v);
  if (v.replace(/\D/g, '').length >= 7) { const p = await api.invoke('resolve', v); if (!p) toast(t('not_found')); return p; }
  toast(t('enter_code_8')); return null;
}
const ACT = {
  nav: (d) => { view = d.v; if (view !== 'chats') api.invoke('chat:open', null); render(); if (view === 'settings') loadQr(); },
  lang: (d) => save({ lang: d.v }),
  obVisible: () => { obVisible = !obVisible; render(); },
  register: () => {
    const name = $('#obName').value.trim(), phone = $('#obPhone').value.trim();
    if (name.length < 2 || phone.replace(/\D/g, '').length < 7) return toast(t('fill_name_phone'));
    save({ registered: true, name, phone, visible: obVisible });
  },
  openChat: (d) => openChat(d.p),
  call: (d) => startCall(peerOf(d.p), { video: false }),
  video: (d) => startCall(peerOf(d.p), { video: true }),
  support: (d) => startCall(peerOf(d.p), { support: true }),
  saveContact: (d) => { const p = peerOf(d.p); api.invoke('contact:add', { code: p.code, name: p.name, phone: p.phone || '', ip: p.ip || '' }); },
  deleteChat: () => modal(`<h3>${esc(t('delete_chat'))}?</h3><div class="acts"><button data-act="closeModal">${esc(t('cancel'))}</button><button class="red" data-act="deleteChatYes">${esc(t('delete'))}</button></div>`),
  deleteChatYes: async () => { await api.invoke('chat:delete', chatPeer); chatMsgs = []; closeModal(); },
  send: () => {
    const v = $('#composer').value; if (!v.trim()) return;
    $('#composer').value = '';
    api.invoke('msg:text', chatPeer, peerOf(chatPeer).name, v.trim());
  },
  attach: async () => { const err = await api.invoke('msg:file', chatPeer, peerOf(chatPeer).name); if (err) toast(t(err)); },
  openFile: (d) => d.path && api.invoke('msg:open', d.path),
  retry: (d) => api.invoke('msg:retry', d.id),
  map: (d) => window.open(`https://www.openstreetmap.org/?mlat=${d.lat}&mlon=${d.lon}#map=16/${d.lat}/${d.lon}`),
  answerRc: async (d) => { await api.invoke('checkin', d.s, '', d.rid, d.p); toast(t('sent')); },
  dismissSos: (d, e) => { e.stopPropagation(); sosPeer = null; render(); },
  addContact: () => modal(`<h3>${esc(t('add_contact'))}</h3><label>${esc(t('full_name'))}</label><input id="acName">
      <label style="display:block;margin-top:8px">${esc(t('code_or_phone'))}</label><input id="acId">
      <div class="acts"><button data-act="closeModal">${esc(t('cancel'))}</button><button class="primary" data-act="addContactYes">${esc(t('save'))}</button></div>`),
  addContactYes: async () => {
    const name = $('#acName').value.trim(); const p = await resolveInput($('#acId').value); if (!p) return;
    await api.invoke('contact:add', { code: p.code, name: name || p.name, phone: p.phone || '', ip: p.ip || '' }); closeModal();
  },
  contactMenu: (d) => {
    const c = S.contacts.find((x) => x.code === d.p); const p = peerOf(d.p);
    modal(`<h3>${esc(p.name)}</h3><div class="muted">${fmt(p.code)}</div><div style="display:flex;flex-direction:column;gap:6px;margin-top:10px">
      ${!c ? `<button data-act="saveContact" data-p="${p.code}">⭐ ${esc(t('save_contact'))}</button>` : `<button data-act="teamToggle" data-p="${p.code}">${c.team ? '➖ ' + esc(t('remove_team')) : '🛡 ' + esc(t('add_team'))}</button>
      <button data-act="rename" data-p="${p.code}">✏️ ${esc(t('rename'))}</button>`}
      <button data-act="block" data-p="${p.code}">🚫 ${esc(t('block'))}</button>
      ${c ? `<button class="red" data-act="removeContact" data-p="${p.code}">🗑 ${esc(t('delete'))}</button>` : ''}</div>
      <div class="acts"><button data-act="closeModal">${esc(t('close'))}</button></div>`);
  },
  teamToggle: (d) => { const c = S.contacts.find((x) => x.code === d.p); api.invoke('contact:patch', d.p, { team: !c.team }); closeModal(); },
  rename: (d) => { const c = S.contacts.find((x) => x.code === d.p); modal(`<h3>✏️ ${esc(t('rename'))}</h3><input id="rnName" value="${esc(c.name)}"><div class="acts"><button data-act="closeModal">${esc(t('cancel'))}</button><button class="primary" data-act="renameYes" data-p="${d.p}">${esc(t('save'))}</button></div>`); },
  renameYes: (d) => { api.invoke('contact:patch', d.p, { name: $('#rnName').value.trim() || d.p }); closeModal(); },
  block: (d) => { api.invoke('contact:patch', d.p, { blocked: true, name: peerOf(d.p).name }); closeModal(); },
  unblock: (d) => api.invoke('contact:patch', d.p, { blocked: false }),
  removeContact: (d) => { api.invoke('contact:remove', d.p); closeModal(); },
  key: (d) => { const i = $('#dial'); dialNum = d.k === '⌫' ? i.value.slice(0, -1) : (i.value + d.k).slice(0, 16); i.value = dialNum; },
  dialGo: async (d) => {
    dialNum = $('#dial').value; const p = await resolveInput(dialNum); if (!p) return;
    if (d.m === 'chat') return openChat(p.code);
    startCall(p, { video: d.m === 'video', support: d.m === 'support' });
  },
  clearHistory: () => api.invoke('history:clear'),
  checkin: (d) => {
    const team = S.contacts.filter((c) => c.team && !c.blocked);
    if (!team.length) return toast(t('team_empty'));
    const note = $('#note').value.trim();
    const go = async () => { const n = await api.invoke('checkin', d.s, note, '', null); closeModal(); toast(t('sent_to_n').replace('%d', n)); };
    if (d.s === 'help') { ACT._help = go; modal(`<h3>🆘 ${esc(t('need_help'))}</h3><p>${esc(t('help_confirm').replace('%d', team.length))}</p><div class="acts"><button data-act="closeModal">${esc(t('cancel'))}</button><button class="red" data-act="helpYes">${esc(t('send'))}</button></div>`); }
    else go();
  },
  helpYes: () => ACT._help && ACT._help(),
  startRc: () => {
    if (!S.contacts.some((c) => c.team && !c.blocked)) return toast(t('team_empty'));
    modal(`<h3>📣 ${esc(t('start_rollcall'))}</h3><label>${esc(t('question'))}</label><input id="rcTitle" value="${esc(t('are_you_safe'))}">
      <div class="acts"><button data-act="closeModal">${esc(t('cancel'))}</button><button class="primary" data-act="startRcYes">${esc(t('send'))}</button></div>`);
  },
  startRcYes: async () => { const rc = await api.invoke('rollcall:start', $('#rcTitle').value.trim() || t('are_you_safe')); closeModal(); if (rc) setTimeout(() => ACT.openRc({ id: rc.id }), 300); },
  openRc: (d) => {
    const rc = S.rollcalls.find((r) => r.id === d.id); if (!rc) return;
    const resp = {}; S.checkins.forEach((m) => { try { if (JSON.parse(m.body).rid === rc.id) resp[m.peer] = m; } catch (e) {} });
    const st = (c) => { const m = resp[c]; if (!m) return null; try { return JSON.parse(m.body); } catch (e) { return {}; } };
    const order = rc.members.slice().sort((a, b) => { const r = (x) => { const o = st(x); return !o ? 1 : o.status === 'help' ? 0 : 2; }; return r(a) - r(b); });
    modal(`<h3>📋 ${esc(rc.title)}</h3>${order.map((code) => { const o = st(code); const c = S.contacts.find((x) => x.code === code);
      return `<div class="row" style="padding:6px 0"><span style="font-size:20px">${!o ? '⏳' : o.status === 'help' ? '🆘' : '✅'}</span><div class="grow"><div class="name">${esc(c ? c.name : code)}</div>
      <div class="muted">${o ? ago(resp[code].time) + (o.note ? ' • ' + esc(o.note) : '') : esc(t('no_answer_yet'))}</div></div>
      ${o && o.lat != null ? `<button class="icon" data-act="map" data-lat="${o.lat}" data-lon="${o.lon}">📍</button>` : ''}<button class="icon" data-act="call" data-p="${code}">📞</button></div>`; }).join('')}
      <div class="acts"><button class="ghost" data-act="rcDelete" data-id="${rc.id}">${esc(t('delete'))}</button><button class="primary" data-act="closeModal">${esc(t('close'))}</button></div>`);
  },
  rcDelete: (d) => { api.invoke('rollcall:delete', d.id); closeModal(); },
  rsGo: async () => { const p = await resolveInput($('#rsCode').value); if (p) startCall(p, { support: true }); },
  set: (d) => save({ [d.k]: d.v }),
  saveProfile: () => {
    const name = $('#stName').value.trim(), phone = $('#stPhone').value.trim();
    if (name.length < 2 || phone.replace(/\D/g, '').length < 7) return toast(t('fill_name_phone'));
    save({ name, phone }); toast(t('saved'));
  },
  newCode: () => modal(`<h3>${esc(t('new_code'))}</h3><p>${esc(t('new_code_warn'))}</p><div class="acts"><button data-act="closeModal">${esc(t('cancel'))}</button><button class="primary" data-act="newCodeYes">${esc(t('ok'))}</button></div>`),
  newCodeYes: async () => { const code = await api.invoke('new-code'); await save({ code }); closeModal(); loadQr(); },
  quit: () => api.invoke('quit'),
  closeModal: () => closeModal(),
  closeModalBg: (d, e) => { if (e.target.classList.contains('mbg')) closeModal(); },
  /* call screen */
  hangup: () => api.invoke('hangup'),
  decline: () => api.invoke('decline'),
  accept: (d) => acceptCall(d.v === '1'),
  mute: () => { RTC.muted = !RTC.muted; if (RTC.mic) RTC.mic.getAudioTracks().forEach((tr) => (tr.enabled = !RTC.muted)); renderCall(); },
  cam: () => toggleCamera(),
  screen: () => RTC.screen ? stopScreen(true) : startScreen(true),
  allowView: () => api.invoke('support:allow', 'view'),
  allowControl: () => api.invoke('support:allow', 'control'),
  stopSupport: () => api.invoke('support:stop'),
  rsKey: (d) => dcSend({ t: 'key', k: d.k }),
  rsText: () => { const v = $('#rsTextIn').value; if (v) { dcSend({ t: 'text', s: v }); $('#rsTextIn').value = ''; } },
};
for (const [k, key] of [['tg-visible', 'visible'], ['tg-sharePhone', 'sharePhone'], ['tg-dnd', 'dnd'], ['tg-autoStart', 'autoStart'], ['tg-internet', 'internet']])
  ACT[k] = () => save({ [key]: !S.settings[key] });

async function save(patch) { S = await api.invoke('save-settings', patch); render(); if (view === 'settings') loadQr(); }
async function loadQr() { const img = $('#myQr'); if (!img) return; img.src = await api.invoke('qr', await api.invoke('my-card')); }

document.addEventListener('click', (e) => { const el = e.target.closest('[data-act]'); if (!el) return; const f = ACT[el.dataset.act]; if (f) { e.preventDefault(); f(el.dataset, e); } });
document.addEventListener('input', (e) => { if (e.target.id === 'contactQ') { contactQ = e.target.value; render(); } if (e.target.id === 'dial') dialNum = e.target.value; });
document.addEventListener('keydown', (e) => {
  if (e.target.id === 'composer' && e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); ACT.send(); }
  if (e.target.id === 'dial' && e.key === 'Enter') ACT.dialGo({ m: 'call' });
});
document.addEventListener('dragover', (e) => { if (chatPeer && view === 'chats') { e.preventDefault(); const p = $('.pane'); if (p) p.classList.add('drop'); } });
document.addEventListener('dragleave', () => { const p = $('.pane'); if (p) p.classList.remove('drop'); });
document.addEventListener('drop', (e) => {
  if (!(chatPeer && view === 'chats')) return; e.preventDefault();
  const files = [...e.dataTransfer.files].map((f) => api.filePath(f)).filter(Boolean);
  if (files.length) api.invoke('msg:drop', chatPeer, peerOf(chatPeer).name, files);
  const p = $('.pane'); if (p) p.classList.remove('drop');
});

/* ======================= calls & WebRTC ======================= */
const RTC = { pc: null, dc: null, mic: null, cam: null, screen: null, videoTx: null, pendingIce: [], remoteSet: false, muted: false, remoteVideo: false, started: false };
let CALL = null, ringer = null, tick = null;

function startCall(peer, opts) {
  if (CALL && CALL.phase !== 'idle' && CALL.phase !== 'ended') return toast(t('busy'));
  RTC.wantCam = !!opts.video;
  api.invoke('dial', peer, opts);
}
function acceptCall(video) { RTC.wantCam = !!video; api.invoke('accept', !!video); }

async function rtcStart(d) {
  rtcClose();
  RTC.started = true; RTC.remoteSet = false; RTC.pendingIce = []; RTC.muted = false; RTC.remoteVideo = false;
  const pc = new RTCPeerConnection({ iceServers: d.iceServers, bundlePolicy: 'max-bundle' });
  RTC.pc = pc;
  pc.onicecandidate = (e) => { if (e.candidate) api.invoke('rtc:out', { ice: e.candidate.candidate, mid: e.candidate.sdpMid, idx: e.candidate.sdpMLineIndex }); };
  pc.ontrack = (e) => {
    if (e.track.kind === 'audio') { $('#remoteAudio').srcObject = new MediaStream([e.track]); }
    else { RTC.remoteStream = new MediaStream([e.track]); const v = $('#rv'); if (v) v.srcObject = RTC.remoteStream;
      e.track.onunmute = () => { RTC.remoteVideo = true; renderCall(); }; }
  };
  pc.ondatachannel = (e) => setupDc(e.channel);
  pc.onconnectionstatechange = () => {
    if (pc.connectionState === 'connected') api.invoke('rtc:state', 'connected');
    if (pc.connectionState === 'failed') api.invoke('rtc:state', 'failed');
  };
  try { RTC.mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } }); }
  catch (e) { RTC.mic = null; toast(t('allow_mic')); }
  if (d.caller) {
    if (RTC.mic) RTC.mic.getAudioTracks().forEach((tr) => pc.addTrack(tr, RTC.mic));
    else pc.addTransceiver('audio', { direction: 'recvonly' });
    RTC.videoTx = pc.addTransceiver('video', { direction: 'sendrecv' });
    setupDc(pc.createDataChannel('ctl'));
    if (d.video) await toggleCamera(true);
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    api.invoke('rtc:out', { sdp: offer.sdp, type: 'offer' });
  } else if (d.video) RTC.wantCam = true;
}
async function rtcSignal(o) {
  const pc = RTC.pc; if (!pc) return;
  if (o.sdp) {
    await pc.setRemoteDescription({ type: o.type, sdp: o.sdp });
    RTC.remoteSet = true;
    for (const c of RTC.pendingIce.splice(0)) pc.addIceCandidate(c).catch(() => {});
    if (o.type === 'offer') {
      if (RTC.mic) RTC.mic.getAudioTracks().forEach((tr) => pc.addTrack(tr, RTC.mic));
      const vt = pc.getTransceivers().find((x) => x.receiver.track && x.receiver.track.kind === 'video');
      if (vt) { vt.direction = 'sendrecv'; RTC.videoTx = vt; }
      if (RTC.wantCam) await toggleCamera(true);
      const ans = await pc.createAnswer();
      await pc.setLocalDescription(ans);
      api.invoke('rtc:out', { sdp: ans.sdp, type: 'answer' });
    }
  } else if (o.ice) {
    const c = { candidate: o.ice, sdpMid: o.mid, sdpMLineIndex: o.idx };
    if (RTC.remoteSet) pc.addIceCandidate(c).catch(() => {}); else RTC.pendingIce.push(c);
  }
}
function setupDc(dc) {
  RTC.dc = dc;
  dc.onmessage = (e) => { try { const ev = JSON.parse(e.data); if (CALL && CALL.support.role === 'target') api.invoke('input', ev); } catch (x) {} };
}
function dcSend(o) { if (RTC.dc && RTC.dc.readyState === 'open') RTC.dc.send(JSON.stringify(o)); }

async function toggleCamera(force) {
  const on = force === undefined ? !RTC.cam : force;
  if (on) {
    try { RTC.cam = await navigator.mediaDevices.getUserMedia({ video: { width: 1280, height: 720 } }); }
    catch (e) { toast(t('no_camera')); RTC.cam = null; return; }
    if (!RTC.screen && RTC.videoTx) await RTC.videoTx.sender.replaceTrack(RTC.cam.getVideoTracks()[0]);
    api.invoke('rtc:cam', true);
  } else {
    if (RTC.cam) RTC.cam.getTracks().forEach((x) => x.stop()); RTC.cam = null;
    if (!RTC.screen && RTC.videoTx) await RTC.videoTx.sender.replaceTrack(null);
    api.invoke('rtc:cam', !!RTC.screen);
  }
  renderCall();
}
async function startScreen(manual) {
  if (manual) await api.invoke('screen:allow');
  try { RTC.screen = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 15 }, audio: false }); }
  catch (e) { RTC.screen = null; if (!manual) api.invoke('support:stop'); return; }
  const track = RTC.screen.getVideoTracks()[0];
  track.onended = () => stopScreen(manual);
  if (RTC.videoTx) await RTC.videoTx.sender.replaceTrack(track);
  if (manual) api.invoke('rtc:cam', true);
  renderCall();
}
async function stopScreen(manual) {
  if (RTC.screen) RTC.screen.getTracks().forEach((x) => x.stop()); RTC.screen = null;
  if (RTC.videoTx) await RTC.videoTx.sender.replaceTrack(RTC.cam ? RTC.cam.getVideoTracks()[0] : null);
  if (manual) api.invoke('rtc:cam', !!RTC.cam);
  renderCall();
}
function rtcClose() {
  for (const s of [RTC.mic, RTC.cam, RTC.screen]) if (s) s.getTracks().forEach((x) => x.stop());
  try { RTC.pc && RTC.pc.close(); } catch (e) {}
  Object.assign(RTC, { pc: null, dc: null, mic: null, cam: null, screen: null, videoTx: null, remoteStream: null, started: false, remoteVideo: false });
}

/* ringing sounds (no audio files needed) */
function tone(pattern) {
  stopTone();
  const ctx = new AudioContext(); ringer = { ctx, timer: null };
  const beep = () => { pattern.forEach(([f, start, len]) => {
    const o = ctx.createOscillator(), g = ctx.createGain(); o.frequency.value = f; g.gain.value = 0.12;
    o.connect(g); g.connect(ctx.destination); o.start(ctx.currentTime + start); o.stop(ctx.currentTime + start + len); }); };
  beep(); ringer.timer = setInterval(beep, 3000);
}
function stopTone() { if (ringer) { clearInterval(ringer.timer); ringer.ctx.close(); ringer = null; } }

function onCall(c) {
  const prev = CALL ? CALL.phase : 'idle';
  CALL = c;
  if (c.phase === 'incoming' && prev !== 'incoming') tone([[880, 0, .35], [660, .45, .35], [880, 1, .35], [660, 1.45, .35]]);
  else if (c.phase === 'outgoing' && c.message === 'ringing') { if (!ringer) tone([[440, 0, 1.2]]); }
  else if (c.phase !== 'incoming' && c.phase !== 'outgoing') stopTone();
  if (c.phase === 'idle') { rtcClose(); $('#overlay').innerHTML = ''; clearInterval(tick); tick = null; return; }
  if (!$('#callRoot')) {
    $('#overlay').innerHTML = `<div class="call" id="callRoot"><video class="remote" id="rv" autoplay playsinline style="display:none"></video>
      <video class="local" id="lv" autoplay playsinline muted style="display:none"></video><div id="viewer"></div><div class="info" id="cinfo"></div><div class="ctrls" id="cctrls"></div></div>`;
    if (RTC.remoteStream) $('#rv').srcObject = RTC.remoteStream;
  }
  if (!tick) tick = setInterval(renderCall, 1000);
  renderCall();
}
function renderCall() {
  const c = CALL; if (!c || !$('#callRoot')) return;
  const p = c.peer || {};
  const sup = c.support || {};
  const helperViewing = sup.role === 'helper' && (sup.mode === 'view' || sup.mode === 'control');
  const showRemote = c.phase === 'active' && (RTC.remoteVideo || helperViewing);
  const rv = $('#rv'), lv = $('#lv');
  rv.style.display = showRemote ? 'block' : 'none';
  const localSrc = RTC.screen && sup.role !== 'target' ? RTC.screen : RTC.cam;
  if (localSrc && lv.srcObject !== localSrc) lv.srcObject = localSrc;
  lv.style.display = localSrc && c.phase === 'active' ? 'block' : 'none';
  const secTxt = c.security === 1 ? '🔒 ' + t('sec_verified') : c.security === 2 ? '⚠ ' + t('sec_changed') : '🔒 ' + t('sec_encrypted');
  let status;
  if (c.phase === 'active') status = c.message === 'connecting_media' ? t('connecting_media') : dur((Date.now() - c.startedAt) / 1000);
  else if (c.phase === 'incoming') status = sup.req ? t('rs_incoming').replace('%s', p.name) : c.wantVideo ? t('incoming_video') : t('incoming');
  else status = t(c.message || 'connecting');
  if (sup.role === 'helper' && c.phase === 'active' && !sup.mode) status = t('rs_waiting').replace('%s', p.name);
  $('#cinfo').innerHTML = showRemote ? '' : `<div class="big" style="background:${color(p.code)}">${esc(initials(p.name))}</div>
    <div style="font-size:28px;font-weight:700">${esc(p.name)}</div><div style="opacity:.75">${fmt(p.code)} • ${c.internet ? '🌐 ' + esc(t('via_internet')) : '📶 ' + esc(t('via_wifi'))}</div>
    <div style="font-size:18px;margin-top:6px">${esc(status)}</div><div class="sec" style="color:${c.security === 2 ? '#ff8a80' : '#cfd8e6'}">${esc(secTxt)}</div>
    ${sup.role === 'target' && (sup.mode === 'view' || sup.mode === 'control') ? `<div class="consent" style="background:#c62828;color:#fff">🔴 ${esc(t(sup.mode === 'control' ? 'rs_banner_control' : 'rs_banner_view'))}: <b>${esc(p.name)}</b><br><button style="margin-top:10px;background:#fff;color:#c62828" data-act="stopSupport">${esc(t('rs_stop'))}</button></div>` : ''}
    ${sup.role === 'target' && c.phase === 'active' && !sup.mode ? consentBox(p) : ''}`;
  // helper: remote screen viewer + control surface
  const viewer = $('#viewer');
  if (helperViewing) {
    if (!viewer.dataset.on) {
      viewer.dataset.on = '1';
      viewer.innerHTML = `<div class="toolbar"><span style="color:#fff;padding:6px">${sup.mode === 'control' ? '🖱 ' + esc(t('rs_controlling')) : '👁 ' + esc(t('rs_viewing'))}: ${esc(p.name)}</span>
        <button data-act="rsKey" data-k="back">◀</button><button data-act="rsKey" data-k="home">⌂</button><button data-act="rsKey" data-k="recents">▢</button>
        <input id="rsTextIn" placeholder="${esc(t('rs_type'))}" style="width:160px;padding:5px"><button data-act="rsText">⏎</button></div>
        <div class="viewer" id="surface" tabindex="0"></div>`;
      bindSurface($('#surface'));
    }
    viewer.querySelector('.toolbar span').textContent = (sup.mode === 'control' ? '🖱 ' + t('rs_controlling') : '👁 ' + t('rs_viewing')) + ': ' + p.name;
    $('#surface').style.cursor = sup.mode === 'control' ? 'crosshair' : 'default';
  } else if (viewer.dataset.on) { viewer.dataset.on = ''; viewer.innerHTML = ''; }
  const btn = (act, icon, label, cls = '', extra = '') => `<div><button class="${cls}" data-act="${act}" ${extra}>${icon}</button><div class="lbl">${esc(label)}</div></div>`;
  let ctrls = '';
  if (c.phase === 'incoming') {
    ctrls = btn('decline', '✖', t('decline'), 'red') + (c.wantVideo ? btn('accept', '📞', t('answer_audio'), 'green', 'data-v="0"') : '') +
      btn('accept', c.wantVideo ? '🎥' : sup.req ? '🛟' : '📞', t('answer'), 'green', `data-v="${c.wantVideo ? 1 : 0}"`);
  } else if (c.phase === 'outgoing' || c.phase === 'active') {
    if (c.phase === 'active') ctrls = btn('mute', RTC.muted ? '🔇' : '🎤', t('mute'), RTC.muted ? 'on' : '') +
      (sup.role ? '' : btn('cam', RTC.cam ? '📷' : '🚫', t('camera'), RTC.cam ? 'on' : '') + btn('screen', '🖥', t('share_screen'), RTC.screen ? 'on' : ''));
    ctrls += btn('hangup', '✆', t('end'), 'red');
  }
  $('#cctrls').innerHTML = ctrls;
}
function consentBox(p) {
  return `<div class="consent"><h3 style="margin-top:0;color:#1A3C6E">🛟 ${esc(t('rs_incoming').replace('%s', p.name))}</h3>
    <p style="font-size:13px">${esc(t('rs_consent_text'))}</p>
    <div class="row"><button class="primary grow" data-act="allowView">👁 ${esc(t('rs_allow_view'))}</button>
    <button class="green grow" data-act="allowControl">🖱 ${esc(t('rs_allow_control'))}</button></div>
    <button class="ghost" style="width:100%;margin-top:6px" data-act="hangup">${esc(t('decline'))}</button></div>`;
}
/** Helper side: turn mouse/keyboard on the remote video into normalised events for the other device. */
function bindSurface(el) {
  const norm = (e) => {
    const v = $('#rv'); const r = v.getBoundingClientRect();
    const vw = v.videoWidth || 16, vh = v.videoHeight || 9;
    const scale = Math.min(r.width / vw, r.height / vh); const w = vw * scale, h = vh * scale;
    const ox = r.left + (r.width - w) / 2, oy = r.top + (r.height - h) / 2;
    return { x: (e.clientX - ox) / w, y: (e.clientY - oy) / h };
  };
  const ok = () => CALL && CALL.support.mode === 'control';
  let last = 0, down = null;
  el.addEventListener('contextmenu', (e) => e.preventDefault());
  el.addEventListener('mousedown', (e) => { if (!ok()) return; el.focus(); const p = norm(e); down = { p, t: Date.now() }; dcSend({ t: 'down', x: p.x, y: p.y, b: e.button }); });
  el.addEventListener('mousemove', (e) => { if (!ok() || Date.now() - last < 33) return; last = Date.now(); const p = norm(e); dcSend({ t: 'move', x: p.x, y: p.y }); });
  el.addEventListener('mouseup', (e) => { if (!ok()) return; const p = norm(e); dcSend({ t: 'up', x: p.x, y: p.y, b: e.button }); down = null; });
  el.addEventListener('wheel', (e) => { if (!ok()) return; e.preventDefault(); dcSend({ t: 'wheel', dy: e.deltaY }); }, { passive: false });
  el.addEventListener('keydown', (e) => { if (!ok()) return; e.preventDefault(); dcSend({ t: 'kd', code: e.code, key: e.key }); });
  el.addEventListener('keyup', (e) => { if (!ok()) return; e.preventDefault(); dcSend({ t: 'ku', code: e.code, key: e.key }); });
}

/* ======================= events from main ======================= */
api.on('changed', async (s) => { S = s; if (CALL === null) CALL = s.call; await reloadChat(); if (!document.querySelector('.mbg')) render(); });
api.on('call', onCall);
api.on('rtc:start', rtcStart);
api.on('rtc:signal', (o) => rtcSignal(o).catch(() => {}));
api.on('rtc:cam', (d) => { RTC.remoteVideo = !!d.on; renderCall(); });
api.on('rtc:end', () => rtcClose());
api.on('support:local', (mode) => { if (mode === 'view' || mode === 'control') startScreen(false); else if (RTC.screen) stopScreen(false); renderCall(); });
api.on('open-chat', (p) => openChat(p));
api.on('sos', (p) => { sosPeer = p; render(); });

(async () => { S = await api.invoke('state'); CALL = S.call; render(); if (S.call && S.call.phase !== 'idle') onCall(S.call); })();
