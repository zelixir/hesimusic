<template>
  <div class="p-4 border rounded">
    <h3 class="font-medium mb-2">调试面板</h3>
    <div class="text-sm text-gray-600 mb-2">最近解析失败的文件：</div>
    <ul class="text-xs">
      <li v-for="(f, idx) in failures" :key="idx" class="flex justify-between items-center py-1">
        <div class="truncate">{{ f }}</div>
        <div class="ml-2">
          <button @click="retryMetadata(f)" class="px-2 py-1 bg-blue-600 text-white rounded text-xs">重试</button>
        </div>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const failures = ref<string[]>([])

function retryMetadata(path: string) {
  // request native to retry metadata
  if ((window as any).musicBridge && (window as any).musicBridge.call) {
    ;(window as any).musicBridge.call('retryMetadata', { path })
  }
}

// placeholder: in real app, fetch recent failures from bridge
failures.value = []
</script>

<style scoped></style>
