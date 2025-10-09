<template>
  <modal :visible="true" title="需要存储权限" size="sm" :show-close="false" :close-on-backdrop="false">
    <p class="text-sm text-gray-600">应用需要访问您的媒体文件以扫描音乐。请授予访问权限或通过 SAF 选择文件夹。</p>
    
    <template #footer>
      <button @click="$emit('cancel')" class="px-3 py-1 bg-gray-200 rounded">取消</button>
      <button @click="onGrantClick" class="px-3 py-1 bg-blue-600 text-white rounded">授予权限</button>
    </template>
  </modal>
</template>

<script setup lang="ts">
import { defineEmits } from 'vue'
import Modal from './Modal.vue'

const emit = defineEmits(['grant', 'cancel'])

function onGrantClick() {
  // call bridge to request SAF / runtime permissions
  if ((window as any).ScanBridge && (window as any).ScanBridge.requestPermissions) {
    ;(window as any).ScanBridge.requestPermissions()
  }
  emit('grant')
}
</script>
