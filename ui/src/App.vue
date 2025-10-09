<template>
  <div class="min-h-screen bg-gray-100 text-gray-900 pb-20">
    <header class="p-4 bg-white shadow flex items-center justify-between">
      <h1 class="text-xl font-semibold">HesiMusic</h1>
      <div>
        <button @click="goScan" class="px-3 py-1 bg-gray-200 rounded mr-2">扫描音乐</button>
      </div>
    </header>
    <div v-if="latestError" class="fixed top-16 right-4 bg-red-600 text-white px-4 py-2 rounded shadow z-50">
      <div class="flex items-center space-x-3">
        <div class="text-sm">{{ latestError.message }}</div>
        <button @click="dismissError" class="px-2 py-1 bg-white text-red-600 rounded text-xs">关闭</button>
      </div>
    </div>

    <main class="p-4">
      <component :is="currentView" @navigate="handleNavigate" />
    </main>

    <footer class="fixed bottom-0 left-0 right-0 bg-white p-3 border-t">
      <playback-status-bar @open-scan="goScan" />
    </footer>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { useErrors } from './services/errorService'
import { useNavigation } from './composables'
import PlaybackStatusBar from './components/PlaybackStatusBar.vue'

const { currentView, goToScan, handleNavigate } = useNavigation()

const { errors } = useErrors()
const latestError = computed(() => errors.value.length ? errors.value[0] : null)

function dismissError() {
  try { errors.value.shift() } catch {}
}
</script>
