import MusicBridge from './musicBridge'
import { reportError } from './errorService'

export type Track = { id: string; title: string; artist: string }

const MusicApi = {
  async getAllTracks(): Promise<Track[]> {
    try {
      const res = await MusicBridge.call('getAllTracks', {})
      if (!res || !Array.isArray(res)) return []
      // ensure each item has id/title/artist
      return res.map((r: any) => ({ id: String(r.id), title: String(r.title || ''), artist: String(r.artist || '') }))
    } catch (e) {
      console.error('getAllTracks error', e)
      try { reportError(e) } catch {}
      return []
    }
  },

  async getTrackDetails(id: string): Promise<Track | null> {
    try {
      const res = await MusicBridge.call('getTrackDetails', { id })
      if (!res) return null
      const r: any = res
      if (typeof r === 'object' && r.id) {
        return { id: String(r.id), title: String(r.title || ''), artist: String(r.artist || '') }
      }
      return null
    } catch (e) {
      console.error('getTrackDetails error', e)
      try { reportError(e) } catch {}
      return null
    }
  },

  async play(id: string) {
    try {
      return await MusicBridge.call('play', { id })
    } catch (e) {
      console.error('play error', e)
      try { reportError(e) } catch {}
      return { ok: false }
    }
  },

  async addToQueue(id: string) {
    try {
      return await MusicBridge.call('addToQueue', { id })
    } catch (e) {
      console.error('addToQueue error', e)
      try { reportError(e) } catch {}
      return { ok: false }
    }
  },

  async getQueue(): Promise<string[]> {
    try {
      const res = await MusicBridge.call('getQueue', {})
      if (!res || !Array.isArray(res)) return []
      return res.map((x: any) => String(x))
    } catch (e) {
      console.error('getQueue error', e)
      try { reportError(e) } catch {}
      return []
    }
  },

  on(name: string, cb: Function) {
    return MusicBridge.on(name, cb)
  }
}

export default MusicApi
