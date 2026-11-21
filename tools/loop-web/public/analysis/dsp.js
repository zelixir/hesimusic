// Shared DSP helpers. Semantics mirror numpy/scipy where noted.

export const TWO_PI = 6.283185307179586476925286766559;

// np.round / std::nearbyint: round half to even.
export function rint(x) {
  const f = Math.floor(x);
  const d = x - f;
  if (d < 0.5) return f;
  if (d > 0.5) return f + 1;
  return (f % 2 === 0) ? f : f + 1;
}

export function clamp(x, lo, hi) {
  return x < lo ? lo : x > hi ? hi : x;
}

const hannPeriodicCache = new Map();
// scipy.signal.get_window("hann", n, fftbins=True)
export function hannPeriodic(n) {
  let w = hannPeriodicCache.get(n);
  if (w) return w;
  w = new Float64Array(n);
  for (let i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos((TWO_PI * i) / n);
  hannPeriodicCache.set(n, w);
  return w;
}

// numpy.hanning(n) — symmetric.
export function hannSymmetric(n) {
  const w = new Float64Array(n);
  const denom = n - 1;
  for (let i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos((TWO_PI * i) / denom);
  return w;
}

export function sortFloat64(src) {
  const out = Float64Array.from(src);
  out.sort();
  return out;
}

export function medianSorted(sorted, n) {
  if (n <= 0) return NaN;
  if (n % 2 === 1) return sorted[n >> 1];
  return 0.5 * (sorted[(n >> 1) - 1] + sorted[n >> 1]);
}

export function percentileLinear(sorted, n, q) {
  if (n <= 0) return NaN;
  if (n === 1) return sorted[0];
  const pos = (q / 100) * (n - 1);
  const lo = Math.floor(pos);
  let hi = Math.ceil(pos);
  if (hi >= n) hi = n - 1;
  const frac = pos - lo;
  return sorted[lo] + (sorted[hi] - sorted[lo]) * frac;
}

export function meanOf(arr) {
  let s = 0;
  for (let i = 0; i < arr.length; i++) s += arr[i];
  return arr.length ? s / arr.length : 0;
}

export function rmsOf(arr, begin = 0, end = arr.length) {
  let s = 0;
  const n = end - begin;
  if (n <= 0) return 0;
  for (let i = begin; i < end; i++) s += arr[i] * arr[i];
  return Math.sqrt(s / n);
}

// Deterministic PRNG for LSH / random projection.
export function mulberry32(seed) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  return {
    next,
    // Box-Muller normal.
    normal() {
      let u = 0;
      let v = 0;
      while (u === 0) u = next();
      while (v === 0) v = next();
      return Math.sqrt(-2 * Math.log(u)) * Math.cos(TWO_PI * v);
    },
  };
}

export function formatSeconds(sec, decimals = 2) {
  if (!Number.isFinite(sec)) return '—';
  return sec.toFixed(decimals);
}
