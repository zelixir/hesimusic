import MusicBridge from './musicBridge'
import { reportError } from './errorService'

export type FolderNode = {
  path: string
  name?: string
  count?: number
  children?: FolderNode[]
}

export type FolderSelection = { uri: string; displayName?: string };
export type PickFolderResult = { path?: string; displayName?: string } | null;

type ScanProgressCb = (p: { count?: number; current?: string; finished?: boolean }) => void

const ScanApi = {
  async pickFolder(): Promise<PickFolderResult> {
    // native should return { path: string, displayName?: string } or null on cancel
    return await MusicBridge.call('pickFolder', {}) as Promise<PickFolderResult>
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
  folders?: FolderSelection[]
  excludes?: FolderSelection[]
  skipShort?: boolean
  skipAmrMid?: boolean
  skipHidden?: boolean
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
        if (method === 'setScanSettings') {
          const res = (window as any).ScanBridge.setScanSettings(JSON.stringify(payload))
          resolve(typeof res === 'string' ? JSON.parse(res) : res)
          return
        }
        if (method === 'getScanSettings') {
          const res = (window as any).ScanBridge.getScanSettings(JSON.stringify(payload))
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
  // Convert to the format expected by native
  const payload = {
    settings: {
      folders: settings.folders?.map(f => ({ uri: f.uri, displayName: f.displayName })) || [],
      excludes: settings.excludes?.map(e => ({ uri: e.uri, displayName: e.displayName })) || [],
      skipShort: settings.skipShort !== undefined ? settings.skipShort : true,
      skipAmrMid: settings.skipAmrMid !== undefined ? settings.skipAmrMid : true,
      skipHidden: settings.skipHidden !== undefined ? settings.skipHidden : true
    }
  }
  return callNative('setScanSettings', payload)
}

export async function loadSettings(): Promise<ScanSettings | null> {
  const r = await callNative('getScanSettings', {})
  if (r?.settings) {
    const s = r.settings
    return {
      folders: s.folders || [],
      excludes: s.excludes || [],
      skipShort: s.skipShort !== undefined ? s.skipShort : true,
      skipAmrMid: s.skipAmrMid !== undefined ? s.skipAmrMid : true,
      skipHidden: s.skipHidden !== undefined ? s.skipHidden : true
    }
  }
  return null
}
