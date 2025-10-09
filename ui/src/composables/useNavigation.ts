import { ref, computed } from 'vue'
import type { ViewType } from '../types'
import SongLibrary from '../components/SongLibrary.vue'
import ScanPage from '../components/ScanPage.vue'

export function useNavigation() {
  const view = ref<ViewType>('library')

  const currentView = computed(() => (view.value === 'library' ? SongLibrary : ScanPage))

  function goToScan() {
    view.value = 'scan'
  }

  function goToLibrary() {
    view.value = 'library'
  }

  function handleNavigate(name: string) {
    if (name === 'library') {
      goToLibrary()
    } else if (name === 'scan') {
      goToScan()
    }
  }

  return {
    view,
    currentView,
    goToScan,
    goToLibrary,
    handleNavigate
  }
}
