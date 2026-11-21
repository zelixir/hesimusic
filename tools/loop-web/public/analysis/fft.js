// Minimal numpy-compatible FFT helpers (port of app/src/main/cpp/loop_engine/fft.cpp).
// Forward FFT is unscaled, inverse is scaled by 1/n, matching numpy.fft.
// Sizes used: 2048 (STFT), 1024 (tempogram / seam flux), 384 (PLP, 2^7 * 3).

const TWO_PI = 6.283185307179586476925286766559;

function isPow2(n) {
  return n > 0 && (n & (n - 1)) === 0;
}

const pow2Plans = new Map();
const triplePlans = new Map();

function pow2Plan(n) {
  let plan = pow2Plans.get(n);
  if (plan) return plan;
  const bits = Math.round(Math.log2(n));
  const rev = new Uint32Array(n);
  for (let i = 0; i < n; i++) {
    let x = i;
    let r = 0;
    for (let b = 0; b < bits; b++) {
      r = (r << 1) | (x & 1);
      x >>>= 1;
    }
    rev[i] = r;
  }
  const half = n >> 1;
  const cos = new Float64Array(half);
  const sin = new Float64Array(half);
  for (let i = 0; i < half; i++) {
    const a = (TWO_PI * i) / n;
    cos[i] = Math.cos(a);
    sin[i] = Math.sin(a);
  }
  plan = { n, rev, cos, sin };
  pow2Plans.set(n, plan);
  return plan;
}

// In-place radix-2 Cooley-Tukey. re/im are Float64Array of length n.
export function fftPow2(re, im, n, inverse) {
  const plan = pow2Plan(n);
  const rev = plan.rev;
  for (let i = 0; i < n; i++) {
    const j = rev[i];
    if (i < j) {
      const tr = re[i];
      re[i] = re[j];
      re[j] = tr;
      const ti = im[i];
      im[i] = im[j];
      im[j] = ti;
    }
  }
  const sign = inverse ? 1 : -1;
  const cos = plan.cos;
  const sin = plan.sin;
  for (let len = 2; len <= n; len <<= 1) {
    const half = len >> 1;
    const step = n / len;
    for (let i = 0; i < n; i += len) {
      for (let j = 0; j < half; j++) {
        const k = j * step;
        const wr = cos[k];
        const wi = sign * sin[k];
        const a = i + j;
        const b = a + half;
        const xr = re[b];
        const xi = im[b];
        const vr = xr * wr - xi * wi;
        const vi = xr * wi + xi * wr;
        const ur = re[a];
        const ui = im[a];
        re[a] = ur + vr;
        im[a] = ui + vi;
        re[b] = ur - vr;
        im[b] = ui - vi;
      }
    }
  }
  if (inverse) {
    const s = 1 / n;
    for (let i = 0; i < n; i++) {
      re[i] *= s;
      im[i] *= s;
    }
  }
}

// n = 3 * 2^k (e.g. 384). Mirrors the recursive mixed-radix path of fft.cpp:
// Y_r = FFT_{n/3}(x[r::3]); X[k] = sum_r W_n^(r k) Y_r[k mod m].
function triplePlan(n) {
  let plan = triplePlans.get(n);
  if (plan) return plan;
  const m = n / 3;
  if (!isPow2(m)) throw new Error('fft: unsupported size ' + n);
  const cos = new Float64Array(3 * n);
  const sin = new Float64Array(3 * n);
  for (let r = 0; r < 3; r++) {
    for (let k = 0; k < n; k++) {
      const a = (TWO_PI * r * k) / n;
      cos[r * n + k] = Math.cos(a);
      sin[r * n + k] = Math.sin(a);
    }
  }
  plan = {
    n,
    m,
    cos,
    sin,
    y0r: new Float64Array(m),
    y0i: new Float64Array(m),
    y1r: new Float64Array(m),
    y1i: new Float64Array(m),
    y2r: new Float64Array(m),
    y2i: new Float64Array(m),
    tr: new Float64Array(n),
    ti: new Float64Array(n),
  };
  triplePlans.set(n, plan);
  return plan;
}

function fftTriple(re, im, n, inverse) {
  const p = triplePlan(n);
  const m = p.m;
  const yr = [p.y0r, p.y1r, p.y2r];
  const yi = [p.y0i, p.y1i, p.y2i];
  for (let r = 0; r < 3; r++) {
    const rr = yr[r];
    const ii = yi[r];
    for (let j = 0; j < m; j++) {
      const src = j * 3 + r;
      rr[j] = re[src];
      ii[j] = im[src];
    }
    fftPow2(rr, ii, m, inverse);
  }
  const sign = inverse ? 1 : -1;
  const cos = p.cos;
  const sin = p.sin;
  const tr = p.tr;
  const ti = p.ti;
  for (let k = 0; k < n; k++) {
    const km = k % m;
    let ar = yr[0][km];
    let ai = yi[0][km];
    for (let r = 1; r < 3; r++) {
      const c = cos[r * n + k];
      const s = sign * sin[r * n + k];
      const xr = yr[r][km];
      const xi = yi[r][km];
      ar += xr * c - xi * s;
      ai += xr * s + xi * c;
    }
    tr[k] = ar;
    ti[k] = ai;
  }
  for (let k = 0; k < n; k++) {
    re[k] = tr[k];
    im[k] = ti[k];
  }
  if (inverse) {
    const s = 1 / n;
    for (let k = 0; k < n; k++) {
      re[k] *= s;
      im[k] *= s;
    }
  }
}

export function fft(re, im, n, inverse) {
  if (n <= 1) return;
  if (isPow2(n)) {
    fftPow2(re, im, n, inverse);
    return;
  }
  if (n % 3 === 0 && isPow2(n / 3)) {
    fftTriple(re, im, n, inverse);
    return;
  }
  throw new Error('fft: unsupported size ' + n);
}

// numpy.fft.rfft: n real samples -> n/2+1 complex bins.
export function rfft(real, n, outRe, outIm) {
  for (let i = 0; i < n; i++) {
    outRe[i] = real[i];
    outIm[i] = 0;
  }
  fft(outRe, outIm, n, false);
}

// numpy.fft.irfft: n/2+1 complex bins -> n real samples (conjugate mirror).
export function irfft(re, im, n, out) {
  const fullRe = fftScratchRe(n);
  const fullIm = fftScratchIm(n);
  fullRe[0] = re[0];
  fullIm[0] = 0;
  for (let k = 1; k <= n / 2; k++) {
    fullRe[k] = re[k];
    fullIm[k] = im[k];
  }
  for (let k = n / 2 + 1; k < n; k++) {
    fullRe[k] = re[n - k];
    fullIm[k] = -im[n - k];
  }
  fft(fullRe, fullIm, n, true);
  for (let i = 0; i < n; i++) out[i] = fullRe[i];
}

const scratchMap = new Map();
function fftScratchRe(n) {
  let s = scratchMap.get(n);
  if (!s) {
    s = { re: new Float64Array(n), im: new Float64Array(n) };
    scratchMap.set(n, s);
  }
  return s.re;
}
function fftScratchIm(n) {
  return scratchMap.get(n).im;
}
