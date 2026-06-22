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
          <select id="ip-select" class="field-select">
            <option value="">Loading…</option>
          </select>
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
        <button id="btn-next" class="btn btn-primary">Next →</button>
      </div>
    </div>
  `;

  const ipSelect = div.querySelector('#ip-select');
  const portInput = div.querySelector('#port-input');
  const portStatus = div.querySelector('#port-status');
  const urlPreview = div.querySelector('#url-preview');

  function updatePreview() {
    const ip = ipSelect.value;
    const port = portInput.value;
    if (ip && port) {
      urlPreview.style.display = '';
      urlPreview.textContent = `🔗 Server will be available at: http://${ip}:${port}`;
    }
  }

  API.get('/api/networks').then(ips => {
    ipSelect.innerHTML = ips.map(ip =>
      `<option value="${ip}" ${ip === cfg.bindAddress ? 'selected' : ''}>${ip}</option>`
    ).join('');
    updatePreview();
  });

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

  div.querySelector('#btn-next').onclick = () => {
    const ip = ipSelect.value;
    const port = parseInt(portInput.value);
    if (!ip) { alert('Please select an IP address.'); return; }
    if (port < 1024 || port > 65535) { alert('Please enter a valid port.'); return; }
    Router.setState({ config: { ...(state.config || {}), bindAddress: ip, port } });
    Router.navigate('folders');
  };

  return div;
};
