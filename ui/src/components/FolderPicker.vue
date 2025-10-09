<template>
  <div>
    <button class="px-3 py-1 bg-gray-200 rounded" @click="open">选择文件夹</button>

    <modal :visible="visible" title="选择文件夹" @close="close">
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

      <template #footer>
        <button class="px-3 py-1 bg-gray-200 rounded" @click="close">取消</button>
        <button class="px-3 py-1 bg-blue-500 text-white rounded" @click="confirm">确定</button>
      </template>
    </modal>
  </div>
</template>

<script lang="ts" setup>
import { useFolderPicker } from '../composables/useFolderPicker'
import Modal from './Modal.vue'

const emit = defineEmits<{ (e: 'picked', paths: string[]): void }>()

const {
  visible,
  breadcrumb,
  currentList,
  selectedMap,
  open,
  close,
  goTo,
  enter,
  toggleSelect,
  selectThis,
  getSelectedPaths
} = useFolderPicker()

function confirm() {
  const picked = getSelectedPaths()
  emit('picked', picked)
  close()
}
</script>
