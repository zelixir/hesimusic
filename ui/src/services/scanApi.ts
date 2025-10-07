import MusicBridge from './musicBridge'
import { reportError } from './errorService'

export type FolderNode = {
  path: string
  name?: string
  count?: number
  children?: FolderNode[]
}

type ScanProgressCb = (p: { count?: number; current?: string; finished?: boolean }) => void

const ScanApi = {
  async pickFolder() {
    // native should return { path: string }
    return await MusicBridge.call('pickFolder', {})
  },

  async requestFolderPermissions(): Promise<{ granted: boolean; folders?: Array<{ uri: string; displayName?: string }> } | null> {
    try {
      console.debug('[scanApi] requestFolderPermissions -> calling MusicBridge')
      const res = await MusicBridge.call('requestFolderPermissions', {})
      console.debug('[scanApi] requestFolderPermissions <- returned', { res })
      return res
    } catch (e) {
      console.error('requestFolderPermissions failed', e)
      try { reportError(e) } catch {}
      throw e
    }
  },

  async listFolders(opts?: { parent?: string }): Promise<FolderNode[]> {
    // native should return a list of FolderNode for the given parent
    const res = await MusicBridge.call('listFolders', opts || {})
    return (res as any) || []
  },

  async startScan(options: any, onProgress?: ScanProgressCb, onError?: (e: any) => void) {
    // options: { folders: string[], skipShort, skipAmrMid, skipHidden }
    // For native, we'll call 'startScan' and then expect native to push progress events via MusicBridge.on('scanProgress')
    console.debug('[scanApi] startScan -> calling MusicBridge', { options })
    const res = await MusicBridge.call('startScan', { options })
    console.debug('[scanApi] startScan <- returned', { res })
    if (onProgress) {
      const off = MusicBridge.on('scanProgress', (p: any) => {
        onProgress(p)
        if (p.finished) off()
      })
    }
    if (onError) {
      const offErr = MusicBridge.on('scanError', (e: any) => {
        onError(e)
      })
    }
    return res
  }
}

export default ScanApi
export type ScanSettings = {
  minDurationMs?: number
  excluded?: string[]
}

function callNative(method: string, payload: any): Promise<any> {
  return new Promise((resolve) => {
    try {
      if ((window as any).ScanBridge) {
        if (method === 'startScan') {
          const res = (window as any).ScanBridge.startScanFromJs(JSON.stringify(payload))
          resolve(typeof res === 'string' ? JSON.parse(res) : res)
          return
        }
        if (method === 'stopScan') {
          const res = (window as any).ScanBridge.stopScanFromJs(payload.scanId)
          resolve(typeof res === 'string' ? JSON.parse(res) : res)
          return
        }
      }
      if ((window as any).musicBridge && (window as any).musicBridge.call) {
        const r = (window as any).musicBridge.call(method, payload)
        resolve(r)
        return
      }
    } catch (e) {
      console.warn('native bridge call failed', e)
      try { reportError(e) } catch {}
    }
    resolve(null)
  })
}

export async function startScan(paths: string[], settings?: ScanSettings) {
  const payload = { paths, settings: settings || {}, excluded: settings?.excluded || [] }
  return callNative('startScan', payload)
}

export async function stopScan(scanId: string) {
  return callNative('stopScan', { scanId })
}

export async function saveSettings(settings: ScanSettings) {
  // just call native if available
  return callNative('setScanSettings', { settings })
}

export async function loadSettings(): Promise<ScanSettings | null> {
  const r = await callNative('getScanSettings', {})
  return r?.settings || null
}
