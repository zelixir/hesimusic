<template>
  <div class="flex items-center justify-between">
    <div class="flex items-center space-x-3">
      <div class="w-12 h-12 bg-gray-200 rounded"></div>
      <div>
        <div class="font-medium">{{ title }}</div>
        <div class="text-sm text-gray-500">{{ artist }}</div>
      </div>
    </div>

    <div class="flex items-center space-x-3">
      <button class="px-3 py-1 bg-gray-200 rounded" @click="prev">Prev</button>
      <button class="px-3 py-1 bg-blue-500 text-white rounded" @click="togglePlay">{{ playing ? 'Pause' : 'Play' }}</button>
      <button class="px-3 py-1 bg-gray-200 rounded" @click="next">Next</button>
      <button class="px-3 py-1 text-sm text-gray-600" @click="$emit('open-scan')">扫描</button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
const title = ref('No track')
const artist = ref('-')
const playing = ref(false)

function togglePlay() {
  playing.value = !playing.value
}

function prev() {
  console.log('prev')
}

function next() {
  console.log('next')
}
</script>
