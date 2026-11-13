'use strict';
const { contextBridge, ipcRenderer, webUtils } = require('electron');
contextBridge.exposeInMainWorld('uor', {
  invoke: (ch, ...a) => ipcRenderer.invoke(ch, ...a),
  on: (ch, fn) => ipcRenderer.on(ch, (e, d) => fn(d)),
  filePath: (f) => { try { return webUtils.getPathForFile(f); } catch (e) { return f.path; } }
});
