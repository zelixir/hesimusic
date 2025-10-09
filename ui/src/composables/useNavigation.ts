import { ref, computed } from 'vue'
import type { ViewType } from '../types'
import SongLibrary from '../components/SongLibrary.vue'
import ScanPage from '../components/ScanPage.vue'

/**
 * Composable for handling navigation between different views in the app
 * @returns Navigation state and methods
 */
export function useNavigation() {
  const view = ref<ViewType>('library')

  const currentView = computed(() => (view.value === 'library' ? SongLibrary : ScanPage))

  /**
   * Navigate to the scan page
   */
  function goToScan() {
    view.value = 'scan'
  }

  /**
   * Navigate to the library page
   */
  function goToLibrary() {
    view.value = 'library'
  }

  /**
   * Handle navigation event from child components
   * @param name - The name of the view to navigate to
   */
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
