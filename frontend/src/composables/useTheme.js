import { ref, watch } from 'vue'

/**
 * Light/dark theme switching
 * - Default: dark
 * - Manual toggle persists in localStorage
 * - apply(m) always sets data-theme so CSS variables respond instantly
 */

const KEY = 'gpn.theme'
const THEME = { LIGHT: 'light', DARK: 'dark' }

// Default to dark unless user explicitly chose light before
const saved = localStorage.getItem(KEY) || THEME.DARK
const mode = ref(saved === THEME.LIGHT ? THEME.LIGHT : THEME.DARK)

function apply(m) {
  document.documentElement.setAttribute('data-theme', m === THEME.DARK ? THEME.DARK : THEME.LIGHT)
}

apply(mode.value)

// Watch for system changes only when user hasn't explicitly chosen
if (window.matchMedia) {
  const mql = window.matchMedia('(prefers-color-scheme: dark)')
  mql.addEventListener('change', (e) => {
    const manual = localStorage.getItem(KEY)
    if (!manual) {
      mode.value = e.matches ? THEME.DARK : THEME.LIGHT
      apply(mode.value)
    }
  })
}

export function useTheme() {
  function setMode(m) {
    mode.value = m
    localStorage.setItem(KEY, m)
    apply(m)
  }
  function toggle() {
    setMode(mode.value === THEME.DARK ? THEME.LIGHT : THEME.DARK)
  }
  return { mode: mode, THEME, setMode, toggle }
}
