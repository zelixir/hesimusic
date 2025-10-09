import { ref, onMounted } from 'vue'
import ScanApi, { saveSettings as saveScanSettings, loadSettings as loadScanSettings } from '../services/scanApi'
import type { FolderSelection, ScanSettings } from '../types'

export function useScanSettings() {
  const skipShort = ref(true)
  const skipAmrMid = ref(true)
  const skipHidden = ref(true)
  const folders = ref<FolderSelection[]>([])
  const excludes = ref<FolderSelection[]>([])

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

  function addFolder(folder: FolderSelection) {
    if (!folders.value.find(x => x.uri === folder.uri)) {
      folders.value.push(folder)
      saveCurrentSettings()
    }
  }

  function removeFolder(index: number) {
    folders.value.splice(index, 1)
    saveCurrentSettings()
  }

  function addExclude(exclude: FolderSelection) {
    if (!excludes.value.find(x => x.uri === exclude.uri)) {
      excludes.value.push(exclude)
      saveCurrentSettings()
    }
  }

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
