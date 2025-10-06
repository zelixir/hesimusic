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
