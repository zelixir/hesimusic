// Mock MusicBridge for development. In Android WebView this will be replaced by the native bridge.

type Track = { id: string; title: string; artist: string }

const tracks: Track[] = [
  { id: '1', title: 'Song A', artist: 'Artist 1' },
  { id: '2', title: 'Song B', artist: 'Artist 2' },
  { id: '3', title: 'Long Song C', artist: 'Artist 3' }
]

let queue: string[] = []

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
      default:
        console.warn('[musicBridge] unknown call', name)
        return null
    }
  },
  on(name: string, cb: Function) {
    // Simple mock: return unsubscribe function
    console.log('[musicBridge] on', name)
    return () => {
      console.log('[musicBridge] off', name)
    }
  }
}
