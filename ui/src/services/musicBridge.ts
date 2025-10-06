// Mock MusicBridge for development. In Android WebView this will be replaced by the native bridge.

type Track = { id: string; title: string; artist: string }

const tracks: Track[] = [
  { id: '1', title: 'Song A', artist: 'Artist 1' },
  { id: '2', title: 'Song B', artist: 'Artist 2' },
  { id: '3', title: 'Long Song C', artist: 'Artist 3' }
]

let queue: string[] = []
let scanListeners: Array<Function> = []

export default {
  async call(name: string, args: any) {
    switch (name) {
      case 'getAllTracks':
        return tracks.map(t => ({ id: t.id, title: t.title, artist: t.artist }))
      case 'getTrackDetails':
        return tracks.find(t => t.id === args.id) || null
      case 'play':
        console.log('[musicBridge] play', args)
        if (args.id) {
          queue = [args.id]
        }
        return { ok: true }
      case 'addToQueue':
        if (args.id) queue.push(args.id)
        return { ok: true, queue }
      case 'getQueue':
        return queue
      case 'pickFolder':
        // Simulate a folder picker
        return { path: '/storage/emulated/0/Music' }
      case 'listFolders':
        // support optional parent argument for lazy loading
        if (args && args.parent) {
          if (args.parent === '/storage/emulated/0') {
            return [
              {
                path: '/storage/emulated/0/Music',
                name: 'Music',
                count: 120
              },
              { path: '/storage/emulated/0/Download', name: 'Download', count: 12 },
              { path: '/storage/emulated/0/Podcasts', name: 'Podcasts', count: 5 }
            ]
          }
          if (args.parent === '/storage/emulated/0/Music') {
            return [
              { path: '/storage/emulated/0/Music/Album1', name: 'Album1', count: 20 },
              { path: '/storage/emulated/0/Music/Album2', name: 'Album2', count: 50 },
              { path: '/storage/emulated/0/Music/Live', name: 'Live', count: 10 }
            ]
          }
          return []
        }
        return [
          { path: '/storage/emulated/0', name: '根目录' }
        ]
      case 'startScan':
        // start a fake scan that emits progress events
        (async () => {
          let count = 0
          const files = ['a.mp3', 'b.mp3', 'c.flac', 'd.mp3']
          for (const f of files) {
            await new Promise(r => setTimeout(r, 300))
            count += 1
            for (const l of scanListeners) l({ count, current: f, finished: false })
          }
          for (const l of scanListeners) l({ count, current: '', finished: true })
        })()
        return { ok: true }
      default:
        console.warn('[musicBridge] unknown call', name)
        return null
    }
  },
  on(name: string, cb: Function) {
    // Simple mock: return unsubscribe function
    console.log('[musicBridge] on', name)
    if (name === 'scanProgress') {
      scanListeners.push(cb)
      return () => {
        const idx = scanListeners.indexOf(cb)
        if (idx >= 0) scanListeners.splice(idx, 1)
      }
    }
    return () => {
      console.log('[musicBridge] off', name)
    }
  }
}
