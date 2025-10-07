import { ref } from 'vue'

type ErrorEntry = { id: string; message: string; time: number }

const errors = ref<ErrorEntry[]>([])

function genId() {
  return 'e' + Math.random().toString(36).slice(2, 9)
}

export function reportError(e: any) {
  try {
    const msg = (e && e.message) ? e.message : String(e)
    errors.value.unshift({ id: genId(), message: msg, time: Date.now() })
    if (errors.value.length > 50) errors.value.pop()
    console.error('[reported error]', msg, e)
  } catch (err) {
    console.error('reportError failed', err)
  }
}

export function useErrors() {
  return { errors }
}

export default { reportError, useErrors }
