export function formatUriToPath(uri: string): string {
  if (!uri || typeof uri !== 'string') return ''
  try {
    // Trim whitespace
    uri = uri.trim()

    // file:// URIs -> remove scheme
    if (uri.startsWith('file://')) {
      // file:///storage/emulated/0/Music or file://C:/path
      // Remove only the scheme
      const without = uri.replace(/^file:\/\//, '')
      // If windows drive like C:/..., return as-is
      if (/^[A-Za-z]:/.test(without)) return decodeURIComponent(without)
      // If it starts with a slash after removing scheme, keep it (unix-style), otherwise ensure single leading slash
      const path = without.startsWith('/') ? without : ('/' + without).replace(/^\/+/, '/')
      // Decode and remove Android device prefix if present to keep output user-friendly
      const decodedPath = decodeURIComponent(path)
      if (decodedPath.startsWith('/storage/emulated/0/')) {
        return decodedPath.replace(/^\/storage\/emulated\/0/, '')
      }
      return decodedPath
    }

    // absolute path already - strip common Android device prefixes to keep user-friendly
    if (uri.startsWith('/')) {
      if (uri.startsWith('/storage/emulated/0/')) {
        return uri.replace('/storage/emulated/0', '')
      }
      if (uri.startsWith('/data/media/0/')) {
        return uri.replace('/data/media/0', '')
      }
      return uri
    }

    // Android SAF / tree/content URIs
    // Example: content://com.android.externalstorage.documents/tree/primary:Music
    // Try to extract the part after 'primary:' and map to /storage/emulated/0/...
    try {
      const decoded = decodeURIComponent(uri)
      // handle tree URIs with primary
      // match primary: segment with a relaxed pattern (capture until next space or end) so any valid path chars are allowed
      const m = decoded.match(/primary:([^\s]+)/i)
      if (m && m[1]) {
        // replace any colon separators with slashes
        const rest = m[1].replace(/:+/g, '/')
        // Safely decode; if decoding fails, return the raw rest without device prefix
        try {
          return decodeURIComponent(rest).replace(/^\/+/, '')
        } catch (e) {
          return rest.replace(/^\/+/, '')
        }
      }
      // handle generic content URIs by showing the last meaningful segment
      if (decoded.startsWith('content://')) {
        // split by / and take last segment that isn't an id-like token
        const parts = decoded.split('/')
        for (let i = parts.length - 1; i >= 0; i--) {
          const p = parts[i]
          if (!p) continue
          // skip segments that look like 'tree' or 'document'
          if (p.toLowerCase().includes('document') || p.toLowerCase().includes('tree')) continue
          // decode the final segment for user-friendly output and strip leading slashes
          return decodeURIComponent(p).replace(/^\/+/, '')
        }
      }
    } catch (e) {
      // ignore decode failures
    }

    // If it contains a colon like 'sdcard:Music' or 'primary:Music', convert to path-like
    if (uri.includes(':')) {
      const parts = uri.split(':')
      // prefer last part as path tail, join with slashes for nested segments and decode
      const tail = parts.slice(1).join('/')
      return decodeURIComponent(tail).replace(/^\/+/, '')
    }

    // fallback: return original
    return uri
  } catch (e) {
    return uri
  }
}

export function friendlyFromUri(uri: string): string {
  try {
    const path = formatUriToPath(uri)
    if (!path) return uri
    // show last path segment
    const parts = path.split('/')
    for (let i = parts.length - 1; i >= 0; i--) {
      if (parts[i]) return parts[i]
    }
    return path
  } catch (e) {
    return uri
  }
}
