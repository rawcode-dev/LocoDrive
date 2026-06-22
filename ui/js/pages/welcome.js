/** Pages namespace — each function returns a DOM element */
const Pages = {};

Pages.welcome = (state) => {
  const div = document.createElement('div');
  div.className = 'welcome-root';
  div.innerHTML = `
    <div class="welcome-icon">🗄️</div>
    <h1 class="welcome-title">LocoDrive</h1>
    <span class="version-badge">v1.0.0</span>
    <p class="welcome-tagline">Share files on your local network with a beautiful, secure server. No technical knowledge required.</p>
    <div class="feature-pills">
      <span class="feature-pill">📁 File Browser</span>
      <span class="feature-pill">👤 User Accounts</span>
      <span class="feature-pill">📱 QR Code</span>
      <span class="feature-pill">🔒 Session Auth</span>
      <span class="feature-pill">📊 Live Dashboard</span>
    </div>
    <div class="welcome-btn-row">
      <button id="btn-start" class="btn btn-primary btn-lg">🚀 Get Started</button>
      ${state.configExists ? '<button id="btn-load" class="btn btn-ghost btn-lg">📂 Load Saved Config</button>' : ''}
    </div>
    <p class="welcome-footer">LocoDrive — LAN only, never internet-accessible</p>
  `;

  div.querySelector('#btn-start').onclick = () => Router.navigate('network');
  if (state.configExists) {
    div.querySelector('#btn-load').onclick = async () => {
      const cfg = await API.get('/api/config');
      Router.setState({ config: cfg });
      Router.navigate('review');
    };
  }
  return div;
};
