<template>
  <div v-if="visible" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50" @click.self="onBackdropClick">
    <div class="bg-white rounded p-4" :class="sizeClass">
      <div class="flex justify-between items-center mb-4">
        <h3 class="font-medium">{{ title }}</h3>
        <button 
          v-if="showClose"
          class="text-gray-500 hover:text-gray-700"
          @click="$emit('close')"
        >
          ✕
        </button>
      </div>
      
      <div class="overflow-auto" :class="contentClass">
        <slot />
      </div>

      <div v-if="$slots.footer" class="mt-4 flex justify-end space-x-2">
        <slot name="footer" />
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  visible: boolean
  title?: string
  showClose?: boolean
  size?: 'sm' | 'md' | 'lg' | 'xl'
  closeOnBackdrop?: boolean
}>(), {
  title: '',
  showClose: true,
  size: 'md',
  closeOnBackdrop: true
})

const emit = defineEmits<{
  (e: 'close'): void
}>()

const sizeClass = computed(() => {
  const sizes = {
    sm: 'w-[320px] max-h-[50vh]',
    md: 'w-[520px] max-h-[70vh]',
    lg: 'w-[720px] max-h-[80vh]',
    xl: 'w-[920px] max-h-[90vh]'
  }
  return sizes[props.size]
})

const contentClass = computed(() => {
  return 'max-h-[60vh]'
})

function onBackdropClick() {
  if (props.closeOnBackdrop) {
    emit('close')
  }
}
</script>
