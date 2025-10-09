import { ref, reactive } from 'vue'
import ScanApi, { FolderNode } from '../services/scanApi'

/**
 * Composable for managing folder picker state and navigation
 * @returns Folder picker state and methods
 */
export function useFolderPicker() {
  const visible = ref(false)
  const breadcrumb = ref<string[]>([])
  const lists = reactive<Record<string, FolderNode[]>>({})
  const currentList = ref<FolderNode[]>([])
  const selectedMap = reactive<Record<string, boolean>>({})

  /**
   * Open the folder picker and load root folders
   */
  async function open() {
    visible.value = true
    breadcrumb.value = []
    const roots = await ScanApi.listFolders()
    lists['/'] = roots
    currentList.value = roots
    // init selection map
    for (const r of roots) if (!(r.path in selectedMap)) selectedMap[r.path] = false
  }

  /**
   * Close the folder picker
   */
  function close() {
    visible.value = false
  }

  /**
   * Navigate to a specific breadcrumb level
   * @param idx - Index in the breadcrumb to navigate to
   */
  function goTo(idx: number) {
    if (idx < 0) return
    breadcrumb.value = breadcrumb.value.slice(0, idx + 1)
    const key = breadcrumbKey(breadcrumb.value)
    currentList.value = lists[key] || []
  }

  /**
   * Generate a key for the breadcrumb path
   * @param bc - Breadcrumb array
   * @returns String key for the path
   */
  function breadcrumbKey(bc: string[]): string {
    if (!bc || bc.length === 0) return '/'
    return bc.join('/')
  }

  /**
   * Enter a folder and load its children
   * @param item - Folder node to enter
   */
  async function enter(item: FolderNode) {
    const children = await ScanApi.listFolders({ parent: item.path })
    const key = breadcrumbKey([...breadcrumb.value, item.path])
    lists[key] = children
    // init selection map for children
    for (const c of children) if (!(c.path in selectedMap)) selectedMap[c.path] = false
    // push breadcrumb and set current
    breadcrumb.value.push(item.path)
    currentList.value = children
  }

  /**
   * Toggle selection state of a folder
   * @param path - Path of the folder
   */
  function toggleSelect(path: string) {
    selectedMap[path] = !selectedMap[path]
  }

  /**
   * Select a specific folder
   * @param item - Folder node to select
   */
  function selectThis(item: FolderNode) {
    selectedMap[item.path] = true
  }

  /**
   * Collect all selected folder paths
   * @returns Array of selected folder paths
   */
  function getSelectedPaths(): string[] {
    const selected: string[] = []
    for (const k in selectedMap) {
      if (selectedMap[k]) selected.push(k)
    }
    return selected
  }

  return {
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
  }
}
