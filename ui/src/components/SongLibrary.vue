<template>
  <div>
    <div class="mb-4 flex items-center">
      <input v-model="query" placeholder="搜索歌曲" class="flex-1 p-2 border rounded" />
      <button class="ml-2 px-3 py-1 bg-blue-500 text-white rounded" @click="refresh">刷新</button>
    </div>

    <track-list :tracks="filteredTracks" @play="onPlay" />
  </div>
</template>

<script lang="ts" setup>
import { ref, computed, onMounted } from 'vue'
import TrackList from './TrackList.vue'
import MusicApi from '../services/musicApi'
import type { Track } from '../types'

const tracks = ref<Track[]>([])
const query = ref('')

const filteredTracks = computed(() => {
  if (!query.value) return tracks.value
  return tracks.value.filter(t => 
    (t.title + ' ' + t.artist).toLowerCase().includes(query.value.toLowerCase())
  )
})

onMounted(async () => {
  tracks.value = await MusicApi.getAllTracks()
})

async function refresh() {
  tracks.value = await MusicApi.getAllTracks()
}

function onPlay(id: string) {
  MusicApi.play(id)
}
</script>
