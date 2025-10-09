<template>
  <div>
    <div v-if="tracks.length === 0" class="text-gray-500">No tracks found.</div>

    <ul>
      <li v-for="t in tracks" :key="t.id" class="p-2 bg-white rounded mb-2 shadow-sm">
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
import { defineProps } from 'vue'
import type { Track } from '../types'

defineProps<{ tracks: Track[] }>()
</script>
