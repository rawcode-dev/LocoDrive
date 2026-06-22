/**
 * api.js - HTTP client for the Java backend REST API
 * All calls go to the local Java server (loopback).
 */
const API = (() => {
  // The Tauri backend exposes the server URL via a Rust command.
  // For the UI, we discover it by polling /api/health on localhost ports.
  let _base = null;

  async function discoverBase() {
    if (_base) return _base;
    // The Java server will have printed its URL; Tauri stores it.
    // We try to read it from the Tauri command, or fall back to defaults.
    if (window.__TAURI__) {
      try {
        const { invoke } = window.__TAURI__.core;
        const url = await invoke('get_server_url');
        if (url) { _base = url; return _base; }
      } catch(e) {}
    }
    // Fallback: probe common ports
    for (const port of [8080, 8081, 8082, 9000]) {
      try {
        const r = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(500) });
        if (r.ok) { _base = `http://127.0.0.1:${port}`; return _base; }
      } catch(_) {}
    }
    return 'http://127.0.0.1:8080'; // last resort
  }

  async function get(path) {
    const base = await discoverBase();
    const res = await fetch(`${base}${path}`);
    return res.json();
  }

  async function post(path, body) {
    const base = await discoverBase();
    const res = await fetch(`${base}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    return res.json();
  }

  return { get, post, getBase: discoverBase };
})();
