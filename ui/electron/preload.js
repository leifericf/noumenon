const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('noumenon', {
  platform: process.platform,
  saveBackends: (data) => ipcRenderer.send('save-backends', data),
  getPort: () => ipcRenderer.sendSync('get-port')
});
