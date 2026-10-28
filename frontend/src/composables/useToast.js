import { reactive } from 'vue'

/**
 * Global toast message system
 * Usage:
 *   import { useToast } from './composables/useToast'
 *   const toast = useToast()
 *   toast.error('Node unreachable')
 *   toast.success('Created successfully')
 *   toast.info('Heads up')
 *   toast.warning('Please note')
 */

const state = reactive({ items: [] })
let seq = 0

function push(message, variant = 'info', timeout = 3500) {
  const id = ++seq
  state.items.push({ id, message, variant })
  if (timeout > 0) {
    setTimeout(() => dismiss(id), timeout)
  }
  return id
}

function dismiss(id) {
  const i = state.items.findIndex(t => t.id === id)
  if (i >= 0) state.items.splice(i, 1)
}

export function useToast() {
  return {
    items: state.items,
    push,
    dismiss,
    success: (msg, t) => push(msg, 'success', t),
    error:   (msg, t) => push(msg, 'error',   t),
    warning: (msg, t) => push(msg, 'warning', t),
    info:    (msg, t) => push(msg, 'info',    t),
  }
}
