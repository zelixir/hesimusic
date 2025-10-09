<template>
  <div class="p-4 border rounded">
    <div class="flex items-center justify-between mb-2">
      <div class="text-sm font-medium">扫描进度</div>
      <div v-if="progress" class="text-xs text-gray-500">已扫描 {{ progress.scannedCount }}</div>
    </div>

    <div class="w-full bg-gray-200 h-3 rounded mb-3">
      <div class="bg-green-500 h-3 rounded" :style="{ width: progressPercent + '%' }"></div>
    </div>

    <div class="text-sm text-gray-700 mb-2">当前文件: {{ progress?.currentPath || '—' }}</div>

    <div class="text-sm font-medium mb-1">日志</div>
    <div class="max-h-40 overflow-auto bg-white p-2 text-xs border rounded">
      <div v-for="(l, idx) in logs" :key="idx" class="py-1">{{ l }}</div>
      <div v-if="logs.length===0" class="text-gray-400">尚无日志</div>
    </div>

    <div class="mt-3">
      <div class="text-sm font-medium">错误</div>
      <div class="max-h-28 overflow-auto bg-white p-2 text-xs border rounded">
        <div v-for="(e, i) in errors" :key="i" class="py-1 text-red-600">{{ e }}</div>
        <div v-if="errors.length===0" class="text-gray-400">无错误</div>
      </div>
      <div class="mt-2 flex justify-end">
        <button class="px-3 py-1 bg-blue-500 text-white rounded text-sm" @click="viewFullLog">查看完整日志</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import type { ScanProgress } from '../types'

const props = defineProps<{ scanId: string | null }>()
const progress = ref<ScanProgress | null>(null)
const logs = ref<string[]>([])
const errors = ref<string[]>([])
const showModal = ref(false)
const modalContent = ref<string | null>(null)

const progressPercent = computed(() => {
  if (!progress.value) return 0
  // Without a known total, approximate cap at 10000 files
  return Math.min(100, (progress.value.scannedCount / 10000) * 100)
})

function handleProgress(id: string, p: any) {
  if (props.scanId !== id) return
  progress.value = { scannedCount: p.scannedCount || 0, foundSongs: p.foundSongs || 0, currentPath: p.currentPath }
  logs.value.unshift(`${new Date().toLocaleTimeString()} - ${p.currentPath || 'file'}`)
  if (logs.value.length > 200) logs.value.pop()
}

function handleError(id: string, err: any) {
  if (props.scanId !== id) return
  const msg = (err && err.message) ? err.message : String(err)
  errors.value.unshift(`${new Date().toLocaleTimeString()} - ${msg}`)
  if (errors.value.length > 200) errors.value.pop()
}

async function viewFullLog() {
  if (!props.scanId) return
  try {
    const res = await (window as any).musicBridge.call('getScanState', JSON.stringify({ scanId: props.scanId }))
    // musicBridge returns stringified JSON
    modalContent.value = res
    showModal.value = true
  } catch (e: any) {
    modalContent.value = `获取日志失败: ${e?.message || String(e)}`
    showModal.value = true
  }
}

onMounted(() => {
  ;(window as any).__music_api_on_scan_progress__ = (id: string, p: any) => handleProgress(id, p)
  ;(window as any).__music_api_on_scan_error__ = (id: string, e: any) => handleError(id, e)
})

onBeforeUnmount(() => {
  ;(window as any).__music_api_on_scan_progress__ = undefined
  ;(window as any).__music_api_on_scan_error__ = undefined
})

</script>

<template #modal>
  <div v-if="showModal" class="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center">
    <div class="bg-white p-4 rounded w-3/4 max-h-3/4 overflow-auto">
      <div class="flex justify-between items-center mb-2">
        <div class="font-medium">完整日志</div>
        <button class="text-sm text-gray-600" @click="showModal = false">关闭</button>
      </div>
      <pre class="text-xs">{{ modalContent }}</pre>
    </div>
  </div>
</template>

<style scoped>
</style>
