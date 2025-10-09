import { ref, onMounted } from 'vue'
import ScanApi, { saveSettings as saveScanSettings, loadSettings as loadScanSettings } from '../services/scanApi'
import type { FolderSelection, ScanSettings } from '../types'

/**
 * Composable for managing scan settings state and operations
 * @returns Scan settings state and methods for managing folders, excludes, and options
 */
export function useScanSettings() {
  const skipShort = ref(true)
  const skipAmrMid = ref(true)
  const skipHidden = ref(true)
  const folders = ref<FolderSelection[]>([])
  const excludes = ref<FolderSelection[]>([])

  /**
   * Load saved scan settings from storage
   */
  async function loadSavedSettings() {
    try {
      const settings = await loadScanSettings()
      if (settings) {
        if (settings.folders) {
          folders.value = settings.folders
        }
        if (settings.excludes) {
          excludes.value = settings.excludes
        }
        if (settings.skipShort !== undefined) {
          skipShort.value = settings.skipShort
        }
        if (settings.skipAmrMid !== undefined) {
          skipAmrMid.value = settings.skipAmrMid
        }
        if (settings.skipHidden !== undefined) {
          skipHidden.value = settings.skipHidden
        }
      }
    } catch (e) {
      console.error('Failed to load settings', e)
    }
  }

  /**
   * Save current scan settings to storage
   */
  async function saveCurrentSettings() {
    try {
      await saveScanSettings({
        folders: folders.value,
        excludes: excludes.value,
        skipShort: skipShort.value,
        skipAmrMid: skipAmrMid.value,
        skipHidden: skipHidden.value
      })
    } catch (e) {
      console.error('Failed to save settings', e)
    }
  }

  /**
   * Add a folder to the scan list
   * @param folder - The folder to add
   */
  function addFolder(folder: FolderSelection) {
    if (!folders.value.find(x => x.uri === folder.uri)) {
      folders.value.push(folder)
      saveCurrentSettings()
    }
  }

  /**
   * Remove a folder from the scan list by index
   * @param index - The index of the folder to remove
   */
  function removeFolder(index: number) {
    folders.value.splice(index, 1)
    saveCurrentSettings()
  }

  /**
   * Add a folder to the exclude list
   * @param exclude - The folder to exclude
   */
  function addExclude(exclude: FolderSelection) {
    if (!excludes.value.find(x => x.uri === exclude.uri)) {
      excludes.value.push(exclude)
      saveCurrentSettings()
    }
  }

  /**
   * Remove a folder from the exclude list by index
   * @param index - The index of the excluded folder to remove
   */
  function removeExclude(index: number) {
    excludes.value.splice(index, 1)
    saveCurrentSettings()
  }

  return {
    skipShort,
    skipAmrMid,
    skipHidden,
    folders,
    excludes,
    loadSavedSettings,
    saveCurrentSettings,
    addFolder,
    removeFolder,
    addExclude,
    removeExclude
  }
}
