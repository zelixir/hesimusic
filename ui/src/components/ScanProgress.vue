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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch, computed } from 'vue'

interface Progress { scannedCount: number; foundSongs: number; currentPath?: string | null }

const props = defineProps<{ scanId: string | null }>()
const progress = ref<Progress | null>(null)
const logs = ref<string[]>([])

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

onMounted(() => {
  ;(window as any).__music_api_on_scan_progress__ = (id: string, p: any) => handleProgress(id, p)
})

onBeforeUnmount(() => {
  ;(window as any).__music_api_on_scan_progress__ = undefined
})

</script>

<style scoped>
</style>
