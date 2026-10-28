import { ref, watch } from 'vue'

/**
 * Light/dark theme switching
 * - Prefer the value the user manually set in localStorage
 * - Otherwise follow the system prefers-color-scheme
 * - On switch, set <html data-theme="light|dark">
 */

const KEY = 'gpn.theme'
const THEME = { LIGHT: 'light', DARK: 'dark', AUTO: 'auto' }

const saved = localStorage.getItem(KEY) || THEME.AUTO
const mode = ref(saved)

function apply(m) {
  const isDark = m === THEME.DARK ||
    (m === THEME.AUTO && window.matchMedia('(prefers-color-scheme: dark)').matches)
  document.documentElement.setAttribute('data-theme', isDark ? THEME.DARK : THEME.LIGHT)
  // Keep theme.css @media working — only let @media take effect in auto mode
  if (m === THEME.AUTO) {
    document.documentElement.removeAttribute('data-theme')
  }
}

apply(mode.value)

// Watch for system changes (in auto mode)
if (window.matchMedia) {
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (mode.value === THEME.AUTO) apply(mode.value)
  })
}

export function useTheme() {
  function setMode(m) {
    mode.value = m
    localStorage.setItem(KEY, m)
    apply(m)
  }
  function toggle() {
    setMode(mode.value === THEME.LIGHT ? THEME.DARK : THEME.LIGHT)
  }
  return { mode: mode, THEME, setMode, toggle }
}
