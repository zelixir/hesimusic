<template>
  <div>
    <h2 class="text-lg font-semibold mb-4">扫描音乐</h2>

    <div class="mb-4 bg-white p-4 rounded shadow">
      <label class="flex items-center space-x-2"><input type="checkbox" v-model="skipShort" /> <span>不扫描60秒以下歌曲</span></label>
      <label class="flex items-center space-x-2 mt-2"><input type="checkbox" v-model="skipAmrMid" /> <span>不扫描 amr/mid</span></label>
      <label class="flex items-center space-x-2 mt-2"><input type="checkbox" v-model="skipHidden" /> <span>不扫描隐藏文件夹</span></label>
    </div>

    <div class="mb-4">
      <h3 class="font-medium">要扫描的文件夹</h3>
      <ul>
        <li v-for="(f, idx) in folders" :key="f" class="flex justify-between items-center p-2 bg-white rounded mb-2">
          <div>{{ f }}</div>
          <div>
            <button class="px-2 py-1 bg-red-100 rounded" @click="removeFolder(idx)">删除</button>
          </div>
        </li>
      </ul>
      <div class="mt-2">
        <folder-picker @picked="onPicked" />
      </div>
    </div>

    <div class="mb-4">
      <button class="px-4 py-2 bg-blue-500 text-white rounded" @click="startScan">开始扫描</button>
      <button class="px-4 py-2 ml-2 bg-gray-200 rounded" @click="$emit('navigate','library')">返回</button>
    </div>

    <div v-if="scanning" class="mt-4 bg-white p-4 rounded shadow">
      <div>已扫描：{{ scannedCount }}</div>
      <div>当前文件：{{ currentFile }}</div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import ScanApi from '../services/scanApi'
import FolderPicker from './FolderPicker.vue'

const skipShort = ref(true)
const skipAmrMid = ref(true)
const skipHidden = ref(true)

const folders = ref<string[]>([])

const scanning = ref(false)
const scannedCount = ref(0)
const currentFile = ref('')

async function pickFolder() {
  const res = await ScanApi.pickFolder()
  const r: any = res
  if (r && r.path) {
    folders.value.push(String(r.path))
  }
}

function onPicked(paths: string[]) {
  for (const p of paths) {
    if (!folders.value.includes(p)) folders.value.push(p)
  }
}

function removeFolder(idx: number) {
  folders.value.splice(idx, 1)
}

async function startScan() {
  scanning.value = true
  scannedCount.value = 0
  currentFile.value = ''
  const onProgress = (p: any) => {
    scannedCount.value = p.count || scannedCount.value
    currentFile.value = p.current || currentFile.value
    if (p.finished) {
      scanning.value = false
    }
  }

  await ScanApi.startScan({ folders: folders.value, skipShort: skipShort.value, skipAmrMid: skipAmrMid.value, skipHidden: skipHidden.value }, onProgress)
}
</script>
