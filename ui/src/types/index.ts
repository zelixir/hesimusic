// Shared TypeScript types for the application

/**
 * Represents a music track in the library
 */
export interface Track {
  id: string
  title: string
  artist: string
  album?: string
  duration?: number
  path?: string
}

/**
 * Represents a selected folder with URI and display name
 */
export interface FolderSelection {
  uri: string
  displayName: string
}

/**
 * Configuration settings for music scanning
 */
export interface ScanSettings {
  folders: FolderSelection[]
  excludes: FolderSelection[]
  skipShort: boolean
  skipAmrMid: boolean
  skipHidden: boolean
  minDurationMs?: number
  excluded?: string[]
}

/**
 * Progress information during a scan operation
 */
export interface ScanProgress {
  scannedCount: number
  foundSongs: number
  currentPath?: string | null
}

/**
 * Error entry for the error service
 */
export interface ErrorEntry {
  id: string
  message: string
  time: number
}

/**
 * Available view types in the application
 */
export type ViewType = 'library' | 'scan'
