Pages.network = (state) => {
  const cfg = state.config || {};
  const div = document.createElement('div');
  div.className = 'step-root';
  div.innerHTML = `
    <div class="step-card">
      <div class="step-header">
        <div class="step-counter">STEP 1 OF 5</div>
        <h2 class="step-title">🌐 Network Setup</h2>
        <p class="step-subtitle">Select the IP address this server will listen on and the port number.</p>
      </div>
      <div class="step-body">
        <div class="field-group">
          <label class="field-label">Local IP Address</label>
          <div id="ip-loading" style="display:flex;align-items:center;gap:10px;padding:10px 0;">
            <div class="spinner"></div>
            <span style="color:var(--text-muted);font-size:0.9rem;">Detecting network interfaces…</span>
          </div>
          <select id="ip-select" class="field-select" style="display:none;"></select>
          <div id="ip-error" style="display:none;" class="banner banner-warn">
            ⚠️ Could not auto-detect IP. Please enter it manually or restart the app.
          </div>
          <p class="field-hint">Select the IP that other devices on your Wi-Fi can reach.</p>
        </div>
        <div class="field-group">
          <label class="field-label">Port Number</label>
          <input id="port-input" type="number" class="field-input" value="${cfg.port || 8080}" min="1024" max="65535" />
          <p id="port-status" class="field-hint"></p>
        </div>
        <div id="url-preview" class="banner banner-info" style="display:none;"></div>
      </div>
      <div class="step-nav">
        <button class="btn btn-ghost" onclick="Router.navigate('welcome')">← Back</button>
        <button id="btn-next" class="btn btn-primary" disabled>Next →</button>
      </div>
    </div>
  `;

  const ipSelect  = div.querySelector('#ip-select');
  const ipLoading = div.querySelector('#ip-loading');
  const ipError   = div.querySelector('#ip-error');
  const portInput = div.querySelector('#port-input');
  const portStatus= div.querySelector('#port-status');
  const urlPreview= div.querySelector('#url-preview');
  const btnNext   = div.querySelector('#btn-next');

  function updatePreview() {
    const ip   = ipSelect.value;
    const port = portInput.value;
    if (ip && port) {
      urlPreview.style.display = '';
      urlPreview.textContent = `🔗 Server will be available at: http://${ip}:${port}`;
    }
  }

  // Retry the /api/networks call up to 15 times with a 1-second delay
  async function loadNetworks(retries = 15, delayMs = 1000) {
    for (let i = 0; i < retries; i++) {
      try {
        const ips = await API.get('/api/networks');
        if (Array.isArray(ips) && ips.length > 0) {
          ipLoading.style.display = 'none';
          ipSelect.innerHTML = ips.map(ip =>
            `<option value="${ip}" ${ip === cfg.bindAddress ? 'selected' : ''}>${ip}</option>`
          ).join('');
          ipSelect.style.display = '';
          btnNext.disabled = false;
          updatePreview();
          return; // success
        }
      } catch (e) {
        // Server not ready yet — wait and retry
      }
      await new Promise(r => setTimeout(r, delayMs));
    }
    // All retries exhausted
    ipLoading.style.display = 'none';
    ipError.style.display = '';
    // Still allow manual navigation even if auto-detect failed
    btnNext.disabled = false;
  }

  loadNetworks();

  ipSelect.onchange = updatePreview;
  portInput.oninput = () => {
    const p = parseInt(portInput.value);
    if (p >= 1024 && p <= 65535) {
      portStatus.textContent = '✅ Port looks valid';
      portStatus.style.color = 'var(--success)';
    } else {
      portStatus.textContent = '⚠️ Port must be between 1024 and 65535';
      portStatus.style.color = 'var(--warning)';
    }
    updatePreview();
  };

  btnNext.onclick = () => {
    const ip   = ipSelect.value;
    const port = parseInt(portInput.value);
    if (!ip) { alert('Please select an IP address.'); return; }
    if (port < 1024 || port > 65535) { alert('Please enter a valid port.'); return; }
    Router.setState({ config: { ...(state.config || {}), bindAddress: ip, port } });
    Router.navigate('folders');
  };

  return div;
};

