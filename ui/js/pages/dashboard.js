Pages.dashboard = (state) => {
  const div = document.createElement('div');
  div.className = 'dashboard-root';

  let _logPoll = null;
  let _statPoll = null;
  let _serverRunning = true;

  div.innerHTML = `
    <!-- Header -->
    <div class="dashboard-header">
      <div style="display:flex;align-items:center;gap:12px;">
        <span style="font-size:28px;">🗄️</span>
        <div>
          <div style="color:var(--text-primary);font-size:18px;font-weight:700;">LocoDrive</div>
          <div style="color:var(--text-secondary);font-size:12px;">Local File Server</div>
        </div>
      </div>
      <div style="display:flex;align-items:center;gap:10px;">
        <span id="server-badge" class="badge badge-running">🟢 Running</span>
        <button id="btn-toggle" class="btn btn-danger btn-sm">⏹ Stop Server</button>
        <button class="btn btn-ghost btn-sm" onclick="Router.navigate('welcome')">⚙️ Reconfigure</button>
      </div>
    </div>

    <!-- Body -->
    <div class="dashboard-body">
      <!-- Left panel -->
      <div class="dash-left">
        <div class="dash-card" style="margin-bottom:14px;">
          <div class="dash-card-title">🔗 Server URL</div>
          <div id="server-url" class="server-url" style="margin:8px 0;">Loading…</div>
          <div style="display:flex;gap:8px;margin-top:10px;">
            <button id="btn-browser" class="btn btn-primary btn-sm" style="flex:1;justify-content:center;">🌐 Open Browser</button>
            <button id="btn-copy" class="btn btn-ghost btn-sm">📋 Copy</button>
          </div>
        </div>

        <div class="dash-card" style="margin-bottom:14px;">
          <div class="dash-card-title">📊 Stats</div>
          <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:8px;">
            <div class="stat-card"><div class="stat-value" id="stat-sessions">0</div><div class="stat-label">Sessions</div></div>
            <div class="stat-card"><div class="stat-value" id="stat-uptime">0s</div><div class="stat-label">Uptime</div></div>
          </div>
        </div>

        <div class="dash-card">
          <div class="dash-card-title">📱 QR Code</div>
          <div id="qr-placeholder" style="margin-top:10px;text-align:center;color:var(--text-secondary);font-size:12px;">
            Scan with your phone to open<br/>the file browser instantly.
          </div>
        </div>
      </div>

      <!-- Right panel - log -->
      <div class="dash-right">
        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;">
          <div class="section-label">📋 Access Log</div>
          <button class="btn btn-ghost btn-sm" id="btn-clear-log">Clear</button>
        </div>
        <div id="log-area" class="log-area" style="flex:1;"></div>
      </div>
    </div>
  `;

  async function loadStatus() {
    try {
      const status = await API.get('/api/status');
      document.getElementById('stat-sessions').textContent = status.sessions || 0;
      const upSec = status.uptimeSeconds || 0;
      const h = Math.floor(upSec / 3600), m = Math.floor((upSec % 3600) / 60), s = upSec % 60;
      document.getElementById('stat-uptime').textContent =
        h > 0 ? `${h}h${m}m` : m > 0 ? `${m}m${s}s` : `${s}s`;
    } catch(_) {}
  }

  async function loadLog() {
    try {
      const entries = await API.get('/api/log');
      const logEl = document.getElementById('log-area');
      if (!logEl) return;
      logEl.innerHTML = entries.map(e => `<div class="log-entry">${e}</div>`).join('');
      logEl.scrollTop = logEl.scrollHeight;
    } catch(_) {}
  }

  async function loadConfig() {
    try {
      const cfg = await API.get('/api/config');
      const urlEl = document.getElementById('server-url');
      const url = cfg.serverUrl || `http://${cfg.bindAddress}:${cfg.port}`;
      if (urlEl) urlEl.textContent = url;

      document.getElementById('btn-browser').onclick = () => {
        if (window.__TAURI__) {
          // Use shell open
          try { window.__TAURI__.shell.open(`${url}/browse/`); } catch(_) {}
        } else {
          window.open(`${url}/browse/`, '_blank');
        }
      };
      document.getElementById('btn-copy').onclick = () => {
        navigator.clipboard.writeText(url).catch(() => {});
        const btn = document.getElementById('btn-copy');
        btn.textContent = '✅ Copied!';
        setTimeout(() => { btn.textContent = '📋 Copy'; }, 1500);
      };
    } catch(_) {}
  }

  div.querySelector('#btn-toggle').onclick = async () => {
    // Toggle is not exposed by the headless server yet — just navigate to reconfigure
    alert('Stop the server by exiting the application or right-clicking the tray icon.');
  };

  div.querySelector('#btn-clear-log').onclick = () => {
    const logEl = document.getElementById('log-area');
    if (logEl) logEl.innerHTML = '';
  };

  // Kick off async init
  loadConfig();
  loadStatus();
  loadLog();
  _statPoll = setInterval(loadStatus, 5000);
  _logPoll  = setInterval(loadLog, 3000);

  // Cleanup on navigation
  const observer = new MutationObserver(() => {
    if (!document.body.contains(div)) {
      clearInterval(_statPoll);
      clearInterval(_logPoll);
      observer.disconnect();
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });

  return div;
};
