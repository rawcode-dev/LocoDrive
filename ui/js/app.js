/**
 * app.js - Application entry point
 * Runs after all scripts are loaded. Bootstraps the app.
 */
(async () => {
  // Wait for the DOM to be ready
  if (document.readyState !== 'complete') {
    await new Promise(resolve => window.addEventListener('load', resolve));
  }

  // Load config from server to check if a saved config exists
  let configExists = false;
  let config = null;
  try {
    config = await API.get('/api/config');
    configExists = config.configExists === true;
  } catch(e) {
    console.warn('Could not reach API on startup:', e);
  }

  // Navigate to welcome page, passing config state
  Router.navigate('welcome', { configExists, config: config || {} });
})();
