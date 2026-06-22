Pages.users = (state) => {
  let users = (state.config && state.config.users) ? [...state.config.users] : [];
  let editingIdx = -1;

  const div = document.createElement('div');
  div.className = 'step-root';
  div.innerHTML = `
    <div class="step-card">
      <div class="step-header">
        <div class="step-counter">STEP 3 OF 5</div>
        <h2 class="step-title">👤 User Accounts</h2>
        <p class="step-subtitle">Create user accounts. You need at least one Admin to launch the server.</p>
      </div>
      <div class="step-body" style="display:flex;gap:24px;">
        <!-- User list -->
        <div style="flex:1;min-width:0;">
          <div class="section-label">Users</div>
          <div id="user-list"></div>
        </div>
        <!-- Add/Edit form -->
        <div style="width:260px;flex-shrink:0;">
          <div class="dash-card">
            <div class="dash-card-title" id="form-title">Add User</div>
            <div class="field-group" style="margin-top:12px;">
              <label class="field-label">Username</label>
              <input id="f-username" type="text" class="field-input" placeholder="e.g. admin" />
            </div>
            <div class="field-group">
              <label class="field-label">Password</label>
              <input id="f-password" type="password" class="field-input" placeholder="Min. 6 characters" />
              <div id="strength-bar" class="strength-bar" style="margin-top:8px;display:none;"><div class="strength-fill"></div></div>
              <div id="strength-text" class="strength-text" style="display:none;"></div>
            </div>
            <div class="field-group">
              <label class="field-label">Role</label>
              <select id="f-role" class="field-select">
                <option value="ADMIN">Admin</option>
                <option value="USER">User</option>
              </select>
            </div>
            <button id="btn-add-user" class="btn btn-success" style="width:100%;justify-content:center;">+ Add User</button>
          </div>
        </div>
      </div>
      <div class="step-nav">
        <button class="btn btn-ghost" onclick="Router.navigate('folders')">← Back</button>
        <button id="btn-next" class="btn btn-primary">Next →</button>
      </div>
    </div>
  `;

  const pwInput = div.querySelector('#f-password');
  const strengthBar = div.querySelector('#strength-bar');
  const strengthText = div.querySelector('#strength-text');

  pwInput.addEventListener('input', () => {
    const pw = pwInput.value;
    if (!pw) { strengthBar.style.display = 'none'; strengthText.style.display = 'none'; return; }
    strengthBar.style.display = '';
    strengthText.style.display = '';
    let strength = 'strength-weak', label = 'WEAK';
    if (pw.length >= 8 && /[A-Z]/.test(pw) && /[0-9]/.test(pw)) { strength = 'strength-strong'; label = 'STRONG'; }
    else if (pw.length >= 6) { strength = 'strength-fair'; label = 'FAIR'; }
    strengthBar.className = `strength-bar ${strength}`;
    strengthText.className = `strength-text ${strength}`;
    strengthText.textContent = label;
  });

  function renderUsers() {
    const list = div.querySelector('#user-list');
    if (users.length === 0) {
      list.innerHTML = '<p style="color:var(--text-secondary);font-size:13px;">No users yet. Add at least one Admin.</p>';
      return;
    }
    list.innerHTML = `
      <table class="data-table">
        <thead><tr><th>Username</th><th>Role</th><th></th></tr></thead>
        <tbody>
          ${users.map((u, i) => `
            <tr>
              <td>${u.username}</td>
              <td><span class="badge ${u.role === 'ADMIN' ? 'badge-admin' : 'badge-user'}">${u.role}</span></td>
              <td><button class="btn btn-danger btn-sm" data-remove="${i}">✕</button></td>
            </tr>
          `).join('')}
        </tbody>
      </table>
    `;
    list.querySelectorAll('[data-remove]').forEach(btn => {
      btn.onclick = () => { users.splice(parseInt(btn.dataset.remove), 1); renderUsers(); };
    });
  }

  renderUsers();

  div.querySelector('#btn-add-user').onclick = () => {
    const username = div.querySelector('#f-username').value.trim();
    const password = div.querySelector('#f-password').value;
    const role = div.querySelector('#f-role').value;
    if (!username) { alert('Please enter a username.'); return; }
    if (password.length < 6) { alert('Password must be at least 6 characters.'); return; }
    if (users.some(u => u.username.toLowerCase() === username.toLowerCase())) {
      alert('A user with this username already exists.'); return;
    }
    users.push({ username, password, role, enabled: true });
    div.querySelector('#f-username').value = '';
    div.querySelector('#f-password').value = '';
    pwInput.dispatchEvent(new Event('input'));
    renderUsers();
  };

  div.querySelector('#btn-next').onclick = () => {
    if (!users.some(u => u.role === 'ADMIN')) {
      alert('You need at least one Admin user.'); return;
    }
    Router.setState({ config: { ...(state.config || {}), users } });
    Router.navigate('review');
  };

  return div;
};
