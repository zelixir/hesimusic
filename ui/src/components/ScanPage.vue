<template>
  <div class="p-6">
    <h2 class="text-2xl font-semibold mb-4">扫描音乐</h2>

    <div class="mb-4">
      <label class="block text-sm font-medium mb-1">扫描路径（多行，每行一个路径）</label>
      <textarea v-model="pathsText" class="w-full border rounded p-2" rows="4"></textarea>
    </div>

    <div class="flex items-center gap-3 mb-4">
      <button @click="onStartScan" class="bg-blue-600 text-white px-4 py-2 rounded">开始扫描</button>
      <button @click="onStopScan" class="bg-gray-300 px-4 py-2 rounded">停止扫描</button>
      <div v-if="scanId" class="ml-4 text-sm text-gray-600">scanId: {{ scanId }}</div>
    </div>

    <div class="border-t pt-4">
      <h3 class="text-lg font-medium mb-2">进度</h3>
      <div v-if="progress" class="text-sm text-gray-700">
        已扫描: {{ progress.scannedCount }}，发现歌曲: {{ progress.foundSongs }}，当前: {{ progress.currentPath }}
      </div>
      <div v-else class="text-sm text-gray-500">尚未开始扫描或无进度数据。</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

type Progress = { scannedCount: number; foundSongs: number; currentPath?: string | null }

const pathsText = ref('/sdcard/Music')
const scanId = ref<string | null>(null)
const progress = ref<Progress | null>(null)

function callBridgeStart(opts: any): string | null {
  // Prefer window.ScanBridge if available (native addJavascriptInterface)
  try {
    if ((window as any).ScanBridge && (window as any).ScanBridge.startScanFromJs) {
      const res = (window as any).ScanBridge.startScanFromJs(JSON.stringify(opts))
      const obj = typeof res === 'string' ? JSON.parse(res) : res
      return obj?.scanId || null
    }
    // fallback to musicBridge if exists
    if ((window as any).musicBridge && (window as any).musicBridge.call) {
      const r = (window as any).musicBridge.call('startScan', opts)
      return r?.scanId || null
    }
  } catch (e) {
    console.warn('start bridge failed', e)
  }
  return null
}

function callBridgeStop(id: string) {
  try {
    if ((window as any).ScanBridge && (window as any).ScanBridge.stopScanFromJs) {
      return (window as any).ScanBridge.stopScanFromJs(id)
    }
    if ((window as any).musicBridge && (window as any).musicBridge.call) {
      return (window as any).musicBridge.call('stopScan', { scanId: id })
    }
  } catch (e) {
    console.warn('stop bridge failed', e)
  }
}

function onStartScan() {
  const paths = pathsText.value.split('\n').map(s => s.trim()).filter(Boolean)
  const opts = { paths, settings: { minDurationMs: 0 }, excluded: [] }
  const id = callBridgeStart(opts)
  if (id) scanId.value = id
}

function onStopScan() {
  if (!scanId.value) return
  callBridgeStop(scanId.value)
  scanId.value = null
  progress.value = null
}

// Hook for window callbacks: window.__music_api_on_scan_progress__ will be called by native
;(window as any).__music_api_on_scan_progress__ = (id: string, p: any) => {
  if (scanId.value !== id) return
  progress.value = { scannedCount: p.scannedCount || 0, foundSongs: p.foundSongs || 0, currentPath: p.currentPath }
}

</script>

<style scoped>
/* minimal styles - use Tailwind in project */
</style>
