Pages.folders = (state) => {
  let folders = (state.config && state.config.sharedFolders) ? [...state.config.sharedFolders] : [];

  const div = document.createElement('div');
  div.className = 'step-root';
  div.innerHTML = `
    <div class="step-card">
      <div class="step-header">
        <div class="step-counter">STEP 2 OF 5</div>
        <h2 class="step-title">📁 Shared Folders</h2>
        <p class="step-subtitle">Choose folders from your computer to share on the network.</p>
      </div>
      <div class="step-body">
        <div class="banner banner-warning" style="margin-bottom:16px;">
          ⚠️ Public folders are accessible to anyone on your Wi-Fi without a password.
        </div>
        <div id="folder-list" style="margin-bottom:16px;"></div>
        <button id="btn-add" class="btn btn-ghost">+ Add Folder</button>
      </div>
      <div class="step-nav">
        <button class="btn btn-ghost" onclick="Router.navigate('network')">← Back</button>
        <button id="btn-next" class="btn btn-primary">Next →</button>
      </div>
    </div>
  `;

  function renderFolders() {
    const list = div.querySelector('#folder-list');
    if (folders.length === 0) {
      list.innerHTML = '<p style="color:var(--text-secondary); font-size:13px;">No folders added yet. Click "+ Add Folder" to begin.</p>';
      return;
    }
    list.innerHTML = `
      <table class="data-table">
        <thead><tr><th>Display Name</th><th>Path</th><th>Public</th><th></th></tr></thead>
        <tbody>
          ${folders.map((f, i) => `
            <tr>
              <td><input class="field-input" style="padding:6px 10px;height:34px;" value="${f.alias}" data-idx="${i}" data-field="alias" /></td>
              <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${f.path}">${f.path}</td>
              <td>
                <label class="toggle">
                  <input type="checkbox" data-idx="${i}" data-field="guestAccessible" ${f.guestAccessible ? 'checked' : ''} />
                  <span class="toggle-slider"></span>
                </label>
              </td>
              <td><button class="btn btn-danger btn-sm" data-remove="${i}">✕</button></td>
            </tr>
          `).join('')}
        </tbody>
      </table>
    `;
    // Wire up events
    list.querySelectorAll('[data-field]').forEach(el => {
      el.addEventListener('change', e => {
        const i = parseInt(e.target.dataset.idx);
        const field = e.target.dataset.field;
        if (e.target.type === 'checkbox') folders[i][field] = e.target.checked;
        else folders[i][field] = e.target.value;
      });
    });
    list.querySelectorAll('[data-remove]').forEach(btn => {
      btn.onclick = () => { folders.splice(parseInt(btn.dataset.remove), 1); renderFolders(); };
    });
  }

  renderFolders();

  div.querySelector('#btn-add').onclick = async () => {
    // Use Tauri dialog to pick a folder
    if (window.__TAURI__) {
      try {
        const { open } = window.__TAURI__.dialog;
        const selected = await open({ directory: true, multiple: false, title: 'Select a folder to share' });
        if (selected) {
          const name = selected.split(/[/\\]/).filter(Boolean).pop();
          folders.push({ alias: name, path: selected, guestAccessible: false, readOnly: true });
          renderFolders();
        }
        return;
      } catch(e) {}
    }
    // Fallback: text input
    const path = prompt('Enter the full path to the folder:');
    if (path) {
      const name = path.split(/[/\\]/).filter(Boolean).pop();
      folders.push({ alias: name, path, guestAccessible: false, readOnly: true });
      renderFolders();
    }
  };

  div.querySelector('#btn-next').onclick = () => {
    if (folders.length === 0) { alert('Please add at least one folder.'); return; }
    Router.setState({ config: { ...(state.config || {}), sharedFolders: folders } });
    Router.navigate('users');
  };

  return div;
};
