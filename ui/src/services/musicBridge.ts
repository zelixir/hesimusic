// Robust MusicBridge implementation.
// Behavior:
// - When a native bridge is present (HesiMusicBridge, musicBridge, ScanBridge), prefer it and
//   throw on failure (to ensure app environment returns real data).
// - Supports requestId async responses via window.__music_api_return__(requestId, result).
// - Provides on(name, cb) for native -> web events. Also exposes compatibility globals
//   used by native code (e.g. __music_api_on_<name>__ and __music_api_emit__).
// - Falls back to a local mock for web development when no native bridge is available.

type Track = { id: string; title: string; artist: string }

declare global {
  interface Window {
    HesiMusicBridge?: { call(name: string, argsJson?: string): string }
    musicBridge?: { call?: (name: string, payload?: any) => any; on?: (name: string, cb: Function) => Function }
    ScanBridge?: { startScanFromJs?: (json: string) => string; stopScanFromJs?: (scanId: string) => string }
    __music_api_return__?: (requestId: string, result: any) => void
    __music_api_emit__?: (name: string, payload: any) => void
    // compatibility shorthand for per-event callbacks
    [key: `__music_api_on_${string}__`]: any
  }
}

import { reportError } from './errorService'

const DEFAULT_TIMEOUT = 10000

function genId(prefix = 'r'): string {
  return prefix + Math.random().toString(36).slice(2, 9)
}

type Pending = {
  resolve: (v: any) => void
  reject: (e: any) => void
  timer?: number
}

const pending = new Map<string, Pending>()

// event listeners for on(name, cb)
const listeners = new Map<string, Set<Function>>()

// --- compatibility: implement the global return handler used by native side ---
const originalReturn = window.__music_api_return__
window.__music_api_return__ = function (requestId: string, result: any) {
  try {
    console.debug('[musicBridge] __music_api_return__ called', { requestId, result })
    if (originalReturn) {
      // allow previous handler to run too
  try { originalReturn(requestId, result) } catch (err) { try { reportError(err) } catch {} }
    }
    const p = pending.get(requestId)
    if (p) {
      pending.delete(requestId)
      if (p.timer) clearTimeout(p.timer)
      p.resolve(result)
      return
    }
    // If no pending request, ignore.
  } catch (e) {
    console.error('__music_api_return__ error', e)
    try { reportError(e) } catch {}
  }
}

// global emit used by native to push events: window.__music_api_emit__(name, payload)
window.__music_api_emit__ = function (name: string, payload: any) {
  console.debug('[musicBridge] __music_api_emit__', { name, payload })
  const set = listeners.get(name)
  if (set) {
    for (const cb of Array.from(set)) {
      try { cb(payload) } catch (e) { console.error('event handler error', e); try { reportError(e) } catch {} }
    }
  }
  // also support per-event global handler: __music_api_on_<name>__
  try {
    const key = `__music_api_on_${name}__`
    const fn = (window as any)[key]
    if (typeof fn === 'function') {
      try { fn(payload) } catch (e) { console.error('per-event global handler error', e); try { reportError(e) } catch {} }
    }
  } catch (e) {
    console.error('emit compatibility error', e)
    try { reportError(e) } catch {}
  }
}

function isNativePresent(): boolean {
  return !!(window.HesiMusicBridge || (window.musicBridge && window.musicBridge.call) || window.ScanBridge)
}

async function callNativeBridge(name: string, args: unknown, timeout = DEFAULT_TIMEOUT): Promise<any> {
  // Priority: HesiMusicBridge.call (synchronous string return or null + async callback)
  // then window.musicBridge.call (may return Promise or value), then ScanBridge special-case.

  const requestId = genId('req_')
  console.debug('[musicBridge] callNativeBridge start', { name, args, requestId, timeout })

  // helper to create pending promise
  const createPending = (): Promise<any> => {
    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(() => {
        pending.delete(requestId)
        reject(new Error(`MusicBridge call timeout: ${name}`))
      }, timeout)
      pending.set(requestId, { resolve, reject, timer })
    })
  }

  try {
    if (window.HesiMusicBridge && typeof window.HesiMusicBridge.call === 'function') {
      // send args wrapped with requestId so native can callback
      const payload = JSON.stringify({ requestId, args })
      try {
        console.debug('[musicBridge] calling HesiMusicBridge.call with payload (requestId wrapped) ', { name, payload })
        const res = window.HesiMusicBridge.call(name, payload)
        console.debug('[musicBridge] HesiMusicBridge.call returned (wrapped)', { name, res })
        if (res === null || res === 'null' || typeof res === 'undefined') {
          // Native indicated it will reply asynchronously via window.__music_api_return__.
          // Do not attempt a second compatibility call here because some native
          // implementations return a synchronous fallback when called without the
          // requestId (which leads to duplicate flows: a synchronous fallback
          // plus an async callback when the user actually picks). Instead wait
          // for the async callback to resolve the pending request.
          return await createPending()
        }
        // parse sync result
        try {
          const parsed = JSON.parse(res)
          console.debug('[musicBridge] parsed HesiMusicBridge response', { name, parsed })
          return parsed
        } catch (e) {
          try { reportError(e) } catch {}
          // if native returned a plain string, return it
          console.debug('[musicBridge] HesiMusicBridge returned non-json', { name, res })
          return res
        }
      } catch (e) {
        throw e
      }
    }

    if (window.musicBridge && typeof window.musicBridge.call === 'function') {
      console.debug('[musicBridge] calling window.musicBridge.call', { name, args })
      const r = window.musicBridge.call(name, args)
      console.debug('[musicBridge] window.musicBridge.call returned', { name, r })
      // may be a Promise or sync value
      if (r && typeof (r as Promise<any>).then === 'function') {
        return await (r as Promise<any>)
      }
      if (typeof r === 'undefined' || r === null) {
        // musicBridge.call returned null/undefined: consider this a failure in native environment
        throw new Error(`musicBridge.call returned ${String(r)} for ${name}`)
      }
      return r
    }

    // special ScanBridge functions (some implementations provide dedicated methods)
    if (window.ScanBridge) {
      if (name === 'startScan' && typeof window.ScanBridge.startScanFromJs === 'function') {
        const payload = JSON.stringify(args)
        console.debug('[musicBridge] calling ScanBridge.startScanFromJs', { payload })
        const res = window.ScanBridge.startScanFromJs(payload)
  try { const parsed = JSON.parse(res); console.debug('[musicBridge] ScanBridge.startScanFromJs returned', { parsed }); return parsed } catch (e) { try { reportError(e) } catch {}; console.debug('[musicBridge] ScanBridge.startScanFromJs returned non-json', { res }); return res }
      }
      if (name === 'stopScan' && typeof window.ScanBridge.stopScanFromJs === 'function') {
        console.debug('[musicBridge] calling ScanBridge.stopScanFromJs', { scanId: (args as any)?.scanId })
        const res = window.ScanBridge.stopScanFromJs((args as any)?.scanId)
  try { const parsed = JSON.parse(res); console.debug('[musicBridge] ScanBridge.stopScanFromJs returned', { parsed }); return parsed } catch (e) { try { reportError(e) } catch {}; console.debug('[musicBridge] ScanBridge.stopScanFromJs returned non-json', { res }); return res }
      }
    }

    // no native bridge
    throw new Error('no native bridge available')
  } finally {
    // nothing
  }
}

// --- Mock fallback (preserve original mock behavior) ---
const mockTracks: Track[] = [
  { id: '1', title: 'Song A', artist: 'Artist 1' },
  { id: '2', title: 'Song B', artist: 'Artist 2' },
  { id: '3', title: 'Long Song C', artist: 'Artist 3' }
]
let mockQueue: string[] = []
let mockScanListeners: Array<Function> = []

const MusicBridge = {
  async call(name: string, args: unknown) {
    // If a native bridge is present, strictly use it and throw on failure to ensure app uses real data
    if (isNativePresent()) {
      return callNativeBridge(name, args)
    }

    // fallback to mock behavior in non-native (web) environment
    switch (name) {
      case 'getAllTracks':
        return mockTracks.map(t => ({ id: t.id, title: t.title, artist: t.artist }))
      case 'getTrackDetails':
        return mockTracks.find(t => t.id === (args as any)?.id) || null
      case 'play':
        console.log('[musicBridge:mock] play', args)
        if ((args as any)?.id) mockQueue = [(args as any).id]
        return { ok: true }
      case 'addToQueue':
        if ((args as any)?.id) mockQueue.push((args as any).id)
        return { ok: true, queue: mockQueue }
      case 'getQueue':
        return mockQueue
      case 'pickFolder':
        return { path: '/storage/emulated/0/Music' }
      case 'requestFolderPermissions':
        // simulate user granting permissions and selecting multiple folders
        return { granted: true, folders: [
          { uri: 'content://com.android.externalstorage.documents/tree/primary%3AMusic', displayName: 'Music' },
          { uri: 'content://com.android.externalstorage.documents/tree/primary%3ADownload', displayName: 'Download' }
        ] }
      case 'listFolders':
        if ((args as any) && (args as any).parent) {
          if ((args as any).parent === '/storage/emulated/0') {
            return [
              { path: '/storage/emulated/0/Music', name: 'Music', count: 120 },
              { path: '/storage/emulated/0/Download', name: 'Download', count: 12 },
              { path: '/storage/emulated/0/Podcasts', name: 'Podcasts', count: 5 }
            ]
          }
          if ((args as any).parent === '/storage/emulated/0/Music') {
            return [
              { path: '/storage/emulated/0/Music/Album1', name: 'Album1', count: 20 },
              { path: '/storage/emulated/0/Music/Album2', name: 'Album2', count: 50 },
              { path: '/storage/emulated/0/Music/Live', name: 'Live', count: 10 }
            ]
          }
          return []
        }
        return [{ path: '/storage/emulated/0', name: '根目录' }]
      case 'startScan':
        (async () => {
          let count = 0
          const files = ['a.mp3', 'b.mp3', 'c.flac', 'd.mp3']
          for (const f of files) {
            await new Promise(r => setTimeout(r, 300))
            count += 1
            for (const l of mockScanListeners) l({ count, current: f, finished: false })
            // simulate an intermittent error on second file
            if (count === 2) {
              // emit error via global emit listeners as well
              const set = listeners.get('scanError')
              if (set) for (const cb of Array.from(set)) { try { cb({ message: '模拟权限错误: 访问被拒绝' }) } catch (err) { try { reportError(err) } catch {} } }
              for (const l of mockScanListeners) {
                try { l({ count, current: f, finished: false, error: { message: '模拟权限错误: 访问被拒绝' } }) } catch (err) { try { reportError(err) } catch {} }
              }
            }
          }
          for (const l of mockScanListeners) l({ count, current: '', finished: true })
        })()
        return { ok: true }
      default:
        console.warn('[musicBridge:mock] unknown call', name)
        return null
    }
  },

  on(name: string, cb: Function) {
    // If native supports on(), delegate
    try {
      if (window.musicBridge && typeof window.musicBridge.on === 'function') {
        return window.musicBridge.on(name, cb)
      }
    } catch (e) {
      console.warn('delegate on to native failed', e)
    }

    // Register in local listeners
    if (!listeners.has(name)) listeners.set(name, new Set())
    listeners.get(name)!.add(cb)

    // also add for mock scan compatibility
    if (name === 'scanProgress') {
      mockScanListeners.push(cb)
    }

    // provide unsubscribe
    return () => {
      const set = listeners.get(name)
      if (set) {
        set.delete(cb)
        if (set.size === 0) listeners.delete(name)
      }
      if (name === 'scanProgress') {
        const idx = mockScanListeners.indexOf(cb)
        if (idx >= 0) mockScanListeners.splice(idx, 1)
      }
    }
  }
}

export default MusicBridge
