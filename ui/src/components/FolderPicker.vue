
<template>
  <div>
    <button class="px-3 py-1 bg-gray-200 rounded" @click="open">选择文件夹</button>

    <div v-if="visible" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
      <div class="bg-white rounded p-4 w-[520px] max-h-[70vh] overflow-auto">
        <h3 class="font-medium mb-2">选择文件夹</h3>

        <div class="mb-2 text-sm text-gray-600">
          <span v-for="(b, idx) in breadcrumb" :key="b" class="mr-2">
            <a href="#" @click.prevent="goTo(idx)" class="underline">{{ b }}</a>
            <span v-if="idx < breadcrumb.length - 1">/</span>
          </span>
        </div>

        <div>
          <ul>
            <li v-for="item in currentList" :key="item.path" class="flex items-center justify-between p-2 hover:bg-gray-50 rounded">
              <div class="flex items-center space-x-2">
                <input type="checkbox" :checked="selectedMap[item.path]" @change="toggleSelect(item.path)" />
                <a href="#" @click.prevent="enter(item)">{{ item.name || item.path }}</a>
                <div class="text-xs text-gray-500">{{ item.count ? '(' + item.count + ')' : '' }}</div>
              </div>
              <div>
                <button class="px-2 py-1 text-sm text-gray-600" @click="selectThis(item)">选择此文件夹</button>
              </div>
            </li>
          </ul>
        </div>

        <div class="mt-4 flex justify-end space-x-2">
          <button class="px-3 py-1 bg-gray-200 rounded" @click="close">取消</button>
          <button class="px-3 py-1 bg-blue-500 text-white rounded" @click="confirm">确定</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref, reactive } from 'vue'
import ScanApi, { FolderNode } from '../services/scanApi'

const visible = ref(false)
const breadcrumb = ref<string[]>([])
const lists = reactive<Record<string, FolderNode[]>>({})
const currentList = ref<FolderNode[]>([])
const selectedMap = reactive<Record<string, boolean>>({})
const emit = defineEmits<{ (e: 'picked', paths: string[]): void }>()

async function open() {
  visible.value = true
  breadcrumb.value = []
  const roots = await ScanApi.listFolders()
  lists['/'] = roots
  currentList.value = roots
  // init selection map
  for (const r of roots) if (!(r.path in selectedMap)) selectedMap[r.path] = false
}

function close() {
  visible.value = false
}

function goTo(idx: number) {
  // navigate breadcrumb
  if (idx < 0) return
  breadcrumb.value = breadcrumb.value.slice(0, idx + 1)
  const key = breadcrumbKey(breadcrumb.value)
  currentList.value = lists[key] || []
}

function breadcrumbKey(bc: string[]) {
  if (!bc || bc.length === 0) return '/'
  return bc.join('/')
}

async function enter(item: FolderNode) {
  // load children for this folder (lazy)
  const children = await ScanApi.listFolders({ parent: item.path })
  const key = breadcrumbKey([...breadcrumb.value, item.path])
  lists[key] = children
  // init selection map for children
  for (const c of children) if (!(c.path in selectedMap)) selectedMap[c.path] = false
  // push breadcrumb and set current
  breadcrumb.value.push(item.path)
  currentList.value = children
}

function toggleSelect(path: string) {
  selectedMap[path] = !selectedMap[path]
}

function selectThis(item: FolderNode) {
  selectedMap[item.path] = true
}

function collectSelected(out: string[]) {
  for (const k in selectedMap) if (selectedMap[k]) out.push(k)
}

function confirm() {
  const picked: string[] = []
  collectSelected(picked)
  emit('picked', picked)
  visible.value = false
}
</script>
