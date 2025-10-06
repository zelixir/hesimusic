<template>
  <div class="min-h-screen bg-gray-100 text-gray-900 pb-20">
    <header class="p-4 bg-white shadow flex items-center justify-between">
      <h1 class="text-xl font-semibold">HesiMusic</h1>
      <div>
        <button @click="goScan" class="px-3 py-1 bg-gray-200 rounded mr-2">扫描音乐</button>
      </div>
    </header>

    <main class="p-4">
      <component :is="currentView" @navigate="onNavigate" />
    </main>

    <footer class="fixed bottom-0 left-0 right-0 bg-white p-3 border-t">
      <playback-status-bar @open-scan="goScan" />
    </footer>
  </div>
</template>

<script lang="ts" setup>
import { ref, computed } from 'vue'
import SongLibrary from './components/SongLibrary.vue'
import ScanPage from './components/ScanPage.vue'
import PlaybackStatusBar from './components/PlaybackStatusBar.vue'

const view = ref<'library' | 'scan'>('library')

const currentView = computed(() => (view.value === 'library' ? SongLibrary : ScanPage))

function goScan() {
  view.value = 'scan'
}

function onNavigate(name: string) {
  if (name === 'library') view.value = 'library'
}
</script>
