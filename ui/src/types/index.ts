// Shared TypeScript types for the application

export interface Track {
  id: string
  title: string
  artist: string
  album?: string
  duration?: number
  path?: string
}

export interface FolderSelection {
  uri: string
  displayName: string
}

export interface ScanSettings {
  folders: FolderSelection[]
  excludes: FolderSelection[]
  skipShort: boolean
  skipAmrMid: boolean
  skipHidden: boolean
  minDurationMs?: number
  excluded?: string[]
}

export interface ScanProgress {
  scannedCount: number
  foundSongs: number
  currentPath?: string | null
}

export interface ErrorEntry {
  id: string
  message: string
  time: number
}

export type ViewType = 'library' | 'scan'
