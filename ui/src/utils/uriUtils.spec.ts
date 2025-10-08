import { describe, it, expect } from 'vitest'
import { formatUriToPath, friendlyFromUri } from './uriUtils'

describe('uriUtils.formatUriToPath', () => {
  it('returns empty string for empty or non-string input', () => {
    expect(formatUriToPath('')).toBe('')
  })

  it('handles file:// unix style', () => {
    expect(formatUriToPath('file:///storage/emulated/0/Music/song.mp3')).toBe('/Music/song.mp3')
  })

  it('handles file:// Windows drive', () => {
    expect(formatUriToPath('file://C:/Music/song.mp3')).toBe('C:/Music/song.mp3')
  })

  it('returns absolute path as-is', () => {
    expect(formatUriToPath('/data/media/0/Music/song.mp3')).toBe('/Music/song.mp3')
  })

  it('handles content:// tree primary URIs', () => {
    const uri = 'content://com.android.externalstorage.documents/tree/primary:Music%2FAlbums'
    expect(formatUriToPath(uri)).toBe('Music/Albums')
  })

  it('handles generic content:// URIs by returning last meaningful segment', () => {
    const uri = 'content://com.android.providers.media.documents/document/audio%3A1234'
    // decoded final segment should be 'audio:1234' and leading slashes removed
    expect(formatUriToPath(uri)).toBe('audio:1234')
  })

  it('converts colon-style URIs like sdcard:Music to path', () => {
    expect(formatUriToPath('sdcard:Music/Pop')).toBe('Music/Pop')
    expect(formatUriToPath('primary:Music')).toBe('Music')
  })

  it('falls back to original for unknown formats', () => {
    expect(formatUriToPath('some-random-uri')).toBe('some-random-uri')
  })
})

describe('uriUtils.friendlyFromUri', () => {
  it('returns last path segment for file uri', () => {
    expect(friendlyFromUri('file:///storage/emulated/0/Music/Artist - Title.mp3')).toBe('Artist - Title.mp3')
  })

  it('returns last segment for primary tree uri', () => {
    expect(friendlyFromUri('content://com.android.externalstorage.documents/tree/primary:Music/Albums')).toBe('Albums')
  })

  it('returns original for empty path result', () => {
    expect(friendlyFromUri('')).toBe('')
  })

  it('handles deep primary paths with multiple segments and encoded chars', () => {
    const uri = 'content://com.android.externalstorage.documents/tree/primary:Music%2FCompilations%2FSummer%25202021%2FTrack%20One.mp3'
    // expect decoded, without device prefix and with nested folders preserved
    expect(formatUriToPath(uri)).toBe('Music/Compilations/Summer 2021/Track One.mp3')
    expect(friendlyFromUri(uri)).toBe('Track One.mp3')
  })

  it('handles deep sdcard colon-style paths', () => {
    const uri = 'sdcard:Music/Genres/Pop/2021/Best Hits/track01.mp3'
    expect(formatUriToPath(uri)).toBe('Music/Genres/Pop/2021/Best Hits/track01.mp3')
    expect(friendlyFromUri(uri)).toBe('track01.mp3')
  })

  it('handles deep file:// unix style with encoded segments', () => {
    const uri = 'file:///storage/emulated/0/Music/Various%20Artists/Album%20Name/Disc%201/01 - Intro.mp3'
    expect(formatUriToPath(uri)).toBe('/Music/Various Artists/Album Name/Disc 1/01 - Intro.mp3')
    expect(friendlyFromUri(uri)).toBe('01 - Intro.mp3')
  })
})
