<template>
  <div class="p-4 border rounded">
    <h3 class="font-medium mb-2">扫描结果</h3>
    <div v-if="!scanId" class="text-sm text-gray-500">请选择扫描任务以查看结果。</div>
    <div v-else>
      <div v-if="loading" class="text-sm text-gray-500">加载中…</div>
      <ul v-else class="divide-y">
        <li v-for="item in results" :key="item.id" class="py-2 flex justify-between items-center">
          <div>
            <div class="text-sm font-medium">{{ item.title || item.path }}</div>
            <div class="text-xs text-gray-500">{{ item.artist }} — {{ item.album }}</div>
          </div>
          <div class="flex items-center gap-2">
            <button @click="applyMerge(item.id)" class="text-sm px-2 py-1 bg-blue-600 text-white rounded">合并</button>
            <button @click="applyIgnore(item.id)" class="text-sm px-2 py-1 bg-gray-200 rounded">忽略</button>
          </div>
        </li>
      </ul>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { startScan, stopScan } from '../services/scanApi'
import { reportError } from '../services/errorService'

const props = defineProps<{ scanId: string | null }>()
const results = ref<any[]>([])
const loading = ref(false)

async function fetchScanResults(id: string) {
  loading.value = true
  try {
    // attempt bridge call
    if ((window as any).musicBridge && (window as any).musicBridge.call) {
      const r = await (window as any).musicBridge.call('getScanResults', { scanId: id })
      results.value = r?.results || []
    } else {
      results.value = []
    }
  } catch (e) {
    console.warn(e)
    try { reportError(e) } catch {}
    results.value = []
  } finally {
    loading.value = false
  }
}

function applyMerge(id: string) {
  if ((window as any).musicBridge && (window as any).musicBridge.call) {
    (window as any).musicBridge.call('mergeScanItem', { id })
  }
}

function applyIgnore(id: string) {
  if ((window as any).musicBridge && (window as any).musicBridge.call) {
    (window as any).musicBridge.call('ignoreScanItem', { id })
  }
}

if (props.scanId) fetchScanResults(props.scanId)

</script>

<style scoped></style>
