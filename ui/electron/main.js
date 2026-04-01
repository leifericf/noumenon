const { app, BrowserWindow, ipcMain } = require('electron');
const { execSync, spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const DEV_URL = 'http://localhost:8280';
const NOUMENON_DIR = path.join(os.homedir(), '.noumenon');
const DAEMON_FILE = path.join(NOUMENON_DIR, 'daemon.edn');
const CONFIG_FILE = path.join(NOUMENON_DIR, 'config.edn');

// --- Config.edn read/write (client-side persistence for connections) ---

function readConfigRaw() {
  try {
    return fs.readFileSync(CONFIG_FILE, 'utf-8');
  } catch (_) {
    return '';
  }
}

function readConfig() {
  try {
    const content = readConfigRaw();
    // Minimal EDN parsing — extract what we need with regex
    const activeMatch = content.match(/:active-backend\s+"([^"]+)"/);
    // For backends, we inject the full config as JSON to the renderer
    return { activeBackend: activeMatch ? activeMatch[1] : 'Local', raw: content };
  } catch (_) {
    return { activeBackend: 'Local', raw: '' };
  }
}

function readDaemonPort() {
  try {
    const content = fs.readFileSync(DAEMON_FILE, 'utf-8');
    const match = content.match(/:port\s+(\d+)/);
    return match ? parseInt(match[1]) : null;
  } catch (_) {
    return null;
  }
}

function isDaemonRunning(port) {
  if (!port) return false;
  try {
    execSync(`curl -sf http://localhost:${port}/health`, { timeout: 3000, stdio: 'pipe' });
    return true;
  } catch (_) {
    return false;
  }
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function startDaemon() {
  if (process.env.NOUMENON_REMOTE) return null;
  try {
    spawn('noum', ['start'], { detached: true, stdio: 'ignore' }).unref();
    for (let i = 0; i < 30; i++) {
      await sleep(500);
      const port = readDaemonPort();
      if (port && isDaemonRunning(port)) return port;
    }
  } catch (_) {}
  return null;
}

async function ensureDaemon() {
  const port = readDaemonPort();
  if (isDaemonRunning(port)) return port;
  console.log('Daemon not running, starting...');
  return await startDaemon();
}

function isDev() {
  return !app.isPackaged;
}

// --- IPC: save backends to config.edn ---

let daemonPort = null;

ipcMain.on('get-port', (event) => {
  event.returnValue = daemonPort;
});

function escapeEdnString(s) {
  return String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

function backendToEdn(b) {
  const name = escapeEdnString(b.name || 'Local');
  const url = escapeEdnString(b.url || 'auto');
  const token = b.token ? `"${escapeEdnString(b.token)}"` : 'nil';
  return `{:name "${name}" :url "${url}" :token ${token}}`;
}

ipcMain.on('save-backends', (_event, data) => {
  try {
    const backends = (data.backends || []).map(backendToEdn).join('\n              ');
    const active = escapeEdnString(data.activeBackend || data['active-backend'] || 'Local');
    const ednContent = `{:backends [${backends}]\n :active-backend "${active}"}\n`;
    fs.mkdirSync(NOUMENON_DIR, { recursive: true });
    fs.writeFileSync(CONFIG_FILE, ednContent, { mode: 0o600 });
  } catch (err) {
    console.error('Failed to save backends:', err.message);
  }
});

async function createWindow() {
  const config = readConfig();
  const isLocal = config.activeBackend === 'Local';
  const envPort = process.env.NOUMENON_PORT ? parseInt(process.env.NOUMENON_PORT) : null;
  const port = envPort || (isLocal ? await ensureDaemon() : null);
  daemonPort = port;

  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    title: 'Noumenon',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  if (isDev()) {
    win.loadURL(DEV_URL);
    win.webContents.openDevTools();
  } else {
    win.loadFile(path.join(__dirname, '..', 'resources', 'public', 'index.html'));
  }

  // Port is now exposed via preload contextBridge (window.noumenon.getPort())
}

function setupAutoUpdate() {
  if (isDev()) return;
  try {
    const { autoUpdater } = require('electron-updater');
    autoUpdater.checkForUpdatesAndNotify();
  } catch (_) {}
}

app.whenReady().then(async () => {
  await createWindow();
  setupAutoUpdate();
});

app.on('window-all-closed', () => {
  app.quit();
});
