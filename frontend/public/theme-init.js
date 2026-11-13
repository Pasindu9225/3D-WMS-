// Runs before first paint so dark mode never flashes white. Kept in sync with src/lib/theme.ts.
(function () {
  var pref = 'system'
  try {
    pref = localStorage.getItem('wms-theme') || 'system'
  } catch (e) {
    // storage blocked: follow the system setting
  }
  var dark = pref === 'dark' || (pref === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)
  document.documentElement.classList.toggle('dark', dark)
})()
