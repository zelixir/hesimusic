<template>
  <div class="fixed inset-0 bg-black bg-opacity-40 flex items-center justify-center">
    <div class="bg-white p-4 rounded w-96">
      <h3 class="font-medium mb-2">需要存储权限</h3>
      <p class="text-sm text-gray-600 mb-4">应用需要访问您的媒体文件以扫描音乐。请授予访问权限或通过 SAF 选择文件夹。</p>
      <div class="flex justify-end gap-2">
        <button @click="$emit('cancel')" class="px-3 py-1 bg-gray-200 rounded">取消</button>
        <button @click="onGrantClick" class="px-3 py-1 bg-blue-600 text-white rounded">授予权限</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { defineEmits } from 'vue'

const emit = defineEmits(['grant', 'cancel'])

function onGrantClick() {
  // call bridge to request SAF / runtime permissions
  if ((window as any).ScanBridge && (window as any).ScanBridge.requestPermissions) {
    ;(window as any).ScanBridge.requestPermissions()
  }
  emit('grant')
}
</script>

<style scoped></style>
