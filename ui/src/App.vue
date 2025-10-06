<template>
  <div class="min-h-screen bg-gray-100 text-gray-900">
    <header class="p-4 bg-white shadow">
      <h1 class="text-xl font-semibold">HesiMusic</h1>
    </header>

    <main class="p-4">
      <track-list />
    </main>

    <footer class="fixed bottom-0 left-0 right-0 bg-white p-3 border-t">
      <div class="flex items-center justify-between">
        <div>
          <div class="font-medium">No track</div>
          <div class="text-sm text-gray-500">-</div>
        </div>
        <div>
          <button class="px-3 py-1 bg-blue-500 text-white rounded">Play</button>
        </div>
      </div>
    </footer>
  </div>
</template>

<script lang="ts">
import TrackList from './components/TrackList.vue'
export default {
  name: 'App',
  components: { TrackList }
}
</script>
