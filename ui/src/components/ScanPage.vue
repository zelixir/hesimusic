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
        <li v-for="(f, idx) in folders" :key="f.uri" class="flex justify-between items-center p-2 bg-white rounded mb-2">
          <div class="flex flex-col">
            <div class="text-sm font-medium">{{ f.displayName || friendlyFromUri(f.uri) }}</div>
            <div class="text-xs text-gray-500">{{ f.uri }}</div>
          </div>
          <div>
            <button class="px-2 py-1 text-red-600" @click="removeFolder(idx)" aria-label="删除">
              <iconify-icon icon="lucide:trash-2" width="16" height="16"></iconify-icon>
            </button>
          </div>
        </li>
      </ul>
        <div class="mt-2 flex items-center space-x-2">
          <button class="px-4 py-2 bg-green-600 text-white rounded" :disabled="adding" @click="addFolder">
            <span v-if="!adding">添加文件夹</span>
            <span v-else>请求权限中...</span>
          </button>
          <button class="px-4 py-2 bg-gray-200 rounded" @click="openExclude = !openExclude">管理排除</button>
        </div>

        <div v-if="error" class="mt-2 text-sm text-red-600">{{ error }}</div>
    </div>

      <div v-if="openExclude" class="mb-4 bg-white p-4 rounded shadow">
        <div class="flex items-center justify-between mb-2">
          <div class="text-sm font-medium">排除的文件夹</div>
          <div>
            <button class="px-3 py-1 bg-green-600 text-white rounded" :disabled="adding" @click="addExclude">添加排除</button>
          </div>
        </div>
        <ul>
          <li v-for="(e, i) in excludes" :key="e.uri" class="flex justify-between items-center p-2 bg-white rounded mb-2">
            <div class="flex flex-col">
              <div class="text-sm font-medium">{{ e.displayName || friendlyFromUri(e.uri) }}</div>
              <div class="text-xs text-gray-500">{{ e.uri }}</div>
            </div>
            <div>
              <button class="px-2 py-1 text-red-600" @click="removeExclude(i)">
                <iconify-icon icon="lucide:trash-2" width="16" height="16"></iconify-icon>
              </button>
            </div>
          </li>
        </ul>
      </div>

    <div class="mb-4">
      <button class="px-4 py-2 bg-blue-500 text-white rounded" @click="startScan" :disabled="scanning || folders.length===0"> 
        <span v-if="!scanning">开始扫描</span>
        <span v-else>扫描中...</span>
      </button>
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
import { reportError } from '../services/errorService'

const skipShort = ref(true)
const skipAmrMid = ref(true)
const skipHidden = ref(true)

// folders now holds objects returned from requestFolderPermissions: { uri, displayName }
  const folders = ref<Array<{ uri: string; displayName?: string }>>([])
  const excludes = ref<Array<{ uri: string; displayName?: string }>>([])

const scanning = ref(false)
const scannedCount = ref(0)
const currentFile = ref('')

const adding = ref(false)
const error = ref<string | null>(null)
const openExclude = ref(false)

  async function addExclude() {
    error.value = null
    adding.value = true
    console.debug('[ScanPage] addExclude start')
    try {
      const res = await ScanApi.requestFolderPermissions()
      console.debug('[ScanPage] addExclude got response', { res })
      if (!res) {
        error.value = '未能获得权限返回值'
        return
      }
      if (!res.granted) {
        error.value = '用户未授予文件夹访问权限'
        return
      }
      if (res.folders && res.folders.length > 0) {
        for (const f of res.folders) {
          if (!excludes.value.find(x => x.uri === f.uri)) excludes.value.push(f)
        }
      }
    } catch (e: any) {
      console.error('addExclude error', e)
      try { reportError(e) } catch {}
      error.value = (e && e.message) ? e.message : String(e)
    } finally {
      adding.value = false
    }
  }

  function removeExclude(idx: number) {
    excludes.value.splice(idx, 1)
  }

function friendlyFromUri(uri: string) {
  try {
    // show last path segment
    const parts = uri.split('/')
    return parts[parts.length - 1] || uri
  } catch (e) {
    return uri
  }
}

async function addFolder() {
  error.value = null
  adding.value = true
  console.debug('[ScanPage] addFolder start')
  try {
    const res = await ScanApi.requestFolderPermissions()
    console.debug('[ScanPage] addFolder got response', { res })
    if (!res) {
      error.value = '未能获得权限返回值'
      return
    }
    if (!res.granted) {
      error.value = '用户未授予文件夹访问权限'
      return
    }
    if (res.folders && res.folders.length > 0) {
      for (const f of res.folders) {
        if (!folders.value.find(x => x.uri === f.uri)) folders.value.push(f)
      }
    }
  } catch (e: any) {
    console.error('addFolder error', e)
    try { reportError(e) } catch {}
    error.value = (e && e.message) ? e.message : String(e)
  } finally {
    adding.value = false
  }
}

function removeFolder(idx: number) {
  folders.value.splice(idx, 1)
}

async function startScan() {
  error.value = null
  scanning.value = true
  scannedCount.value = 0
  currentFile.value = ''

  const folderUris = folders.value.map(f => f.uri)

  const onProgress = (p: any) => {
    try {
      scannedCount.value = p.count || scannedCount.value
      currentFile.value = p.current || currentFile.value
      if (p.error) {
        // show error message from native
        error.value = p.error.message || String(p.error)
      }
      if (p.finished) {
        scanning.value = false
      }
    } catch (e) {
      console.error('onProgress handler error', e)
      try { reportError(e) } catch {}
      error.value = String(e)
      scanning.value = false
    }
  }

  try {
    await ScanApi.startScan({ folders: folderUris, skipShort: skipShort.value, skipAmrMid: skipAmrMid.value, skipHidden: skipHidden.value }, onProgress, (e: any) => {
      console.error('scan error callback', e)
      error.value = (e && e.message) ? e.message : String(e)
    })
  } catch (e: any) {
    console.error('startScan failed', e)
    try { reportError(e) } catch {}
    error.value = (e && e.message) ? e.message : String(e)
    scanning.value = false
  }
}
</script>
