/**
 * router.js - Simple SPA router
 * Manages page rendering and the step indicator bar.
 */
const Router = (() => {
  const WIZARD_STEPS = ['network', 'folders', 'users', 'review', 'dashboard'];
  let _current = null;
  let _state = {};  // shared wizard state

  function updateStepBar(page) {
    const bar = document.getElementById('step-bar');
    const stepIdx = WIZARD_STEPS.indexOf(page);
    if (stepIdx === -1) {
      bar.style.display = 'none';
      return;
    }
    bar.style.display = '';
    document.querySelectorAll('.step-dot').forEach((dot, i) => {
      dot.classList.remove('active', 'completed');
      if (i < stepIdx) dot.classList.add('completed');
      else if (i === stepIdx) dot.classList.add('active');
    });
  }

  function navigate(page, data = {}) {
    _current = page;
    _state = { ..._state, ...data };
    updateStepBar(page);
    const app = document.getElementById('app');
    app.innerHTML = '';
    const el = Pages[page](_state);
    if (el) {
      el.classList.add('fade-in');
      app.appendChild(el);
    }
  }

  function getState() { return _state; }
  function setState(data) { _state = { ..._state, ...data }; }

  return { navigate, getState, setState };
})();
