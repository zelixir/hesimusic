<template>
  <div>
    <div class="mb-4">
      <input v-model="query" placeholder="搜索歌曲" class="w-full p-2 border rounded" />
    </div>

    <div v-if="tracks.length === 0" class="text-gray-500">No tracks found.</div>

    <ul>
      <li v-for="t in filtered" :key="t.id" class="p-2 bg-white rounded mb-2 shadow-sm">
        <div class="flex justify-between items-center">
          <div>
            <div class="font-medium">{{ t.title }}</div>
            <div class="text-sm text-gray-500">{{ t.artist }}</div>
          </div>
          <div>
            <button class="px-2 py-1 bg-gray-200 rounded" @click="$emit('play', t.id)">Play</button>
          </div>
        </div>
      </li>
    </ul>
  </div>
</template>

<script lang="ts" setup>
import { ref, computed, defineProps } from 'vue'

type Track = { id: string; title: string; artist: string }

const props = defineProps<{ tracks: Track[] }>()

const query = ref('')

const tracks = computed(() => props.tracks || [])

const filtered = computed(() => {
  if (!query.value) return tracks.value
  return tracks.value.filter(t => (t.title + ' ' + t.artist).toLowerCase().includes(query.value.toLowerCase()))
})
</script>
