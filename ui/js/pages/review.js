Pages.review = (state) => {
  const cfg = state.config || {};
  const warnings = [];

  // Build warnings
  const folders = cfg.sharedFolders || [];
  const publicFolders = folders.filter(f => f.guestAccessible);
  if (publicFolders.length > 0) warnings.push(`⚠️ ${publicFolders.length} folder(s) are public — accessible without login.`);
  if (cfg.guestEnabled) warnings.push('⚠️ Guest access is enabled globally.');

  const div = document.createElement('div');
  div.className = 'step-root';
  div.innerHTML = `
    <div class="step-card">
      <div class="step-header">
        <div class="step-counter">STEP 4 OF 5</div>
        <h2 class="step-title">🔒 Security Review</h2>
        <p class="step-subtitle">Review your configuration before launching the server.</p>
      </div>
      <div class="step-body" style="overflow-y:auto;">

        <div class="review-card">
          <div class="review-card-title">🌐 Network</div>
          <div class="review-card-body">
            <strong>IP:</strong> ${cfg.bindAddress || '—'}<br/>
            <strong>Port:</strong> ${cfg.port || 8080}<br/>
            <strong>URL:</strong> <span style="color:var(--accent);">http://${cfg.bindAddress}:${cfg.port}</span>
          </div>
        </div>

        <div class="review-card">
          <div class="review-card-title">📁 Shared Folders (${folders.length})</div>
          <div class="review-card-body">
            ${folders.length === 0 ? 'None' :
              folders.map(f =>
                `<div>${f.alias} — <span style="color:var(--text-secondary);font-size:12px;">${f.path}</span>
                  ${f.guestAccessible ? '<span style="color:var(--warning);"> 🔓 Public</span>' : '<span style="color:var(--success);"> 🔒 Private</span>'}
                </div>`
              ).join('')
            }
          </div>
        </div>

        <div class="review-card">
          <div class="review-card-title">👤 Users (${(cfg.users||[]).length})</div>
          <div class="review-card-body">
            ${(cfg.users || []).map(u =>
              `<div>${u.username} — <span class="badge ${u.role === 'ADMIN' ? 'badge-admin' : 'badge-user'}" style="padding:2px 8px;font-size:11px;">${u.role}</span></div>`
            ).join('') || 'None'}
          </div>
        </div>

        ${warnings.length > 0 ? `
          <div class="review-card warning-card">
            <div class="review-card-title">⚠️ Security Warnings</div>
            <div class="review-card-body">
              ${warnings.map(w => `<div class="review-warning-item">${w}</div>`).join('')}
            </div>
          </div>
        ` : `<div class="banner banner-success">✅ Configuration looks good!</div>`}

        <div id="launch-status" style="margin-top:12px;display:none;"></div>
      </div>
      <div class="step-nav">
        <button class="btn btn-ghost" onclick="Router.navigate('users')">← Back</button>
        <button id="btn-launch" class="btn btn-success">🚀 Launch Server</button>
      </div>
    </div>
  `;

  div.querySelector('#btn-launch').onclick = async () => {
    const btn = div.querySelector('#btn-launch');
    const status = div.querySelector('#launch-status');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner"></span> Saving…';
    status.style.display = '';
    status.className = 'banner banner-info';
    status.textContent = 'Saving configuration and starting server…';

    try {
      await API.post('/api/config', cfg);
      status.className = 'banner banner-success';
      status.textContent = '✅ Server started! Loading dashboard…';
      setTimeout(() => Router.navigate('dashboard'), 800);
    } catch(e) {
      status.className = 'banner banner-danger';
      status.textContent = '❌ Failed to save configuration: ' + e.message;
      btn.disabled = false;
      btn.textContent = '🚀 Launch Server';
    }
  };

  return div;
};
