// Small dense linear-algebra helpers (Jacobi eigensolver, QR, power helpers).

export function nextPow2(n) {
  let p = 1;
  while (p < n) p <<= 1;
  return p;
}

// Cyclic Jacobi eigensolver for a symmetric n x n matrix (row-major).
// Returns eigenvalues and eigenvectors (columns of vectors).
export function jacobiEigen(matrix, n, maxSweeps = 40) {
  const a = Float64Array.from(matrix);
  const v = new Float64Array(n * n);
  for (let i = 0; i < n; i++) v[i * n + i] = 1;
  for (let sweep = 0; sweep < maxSweeps; sweep++) {
    let off = 0;
    for (let p = 0; p < n; p++) {
      for (let q = p + 1; q < n; q++) off += a[p * n + q] * a[p * n + q];
    }
    if (off < 1e-22) break;
    for (let p = 0; p < n; p++) {
      for (let q = p + 1; q < n; q++) {
        const apq = a[p * n + q];
        if (Math.abs(apq) < 1e-16) continue;
        const theta = (a[q * n + q] - a[p * n + p]) / (2 * apq);
        const t = Math.sign(theta || 1) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
        const c = 1 / Math.sqrt(t * t + 1);
        const s = t * c;
        for (let k = 0; k < n; k++) {
          const akp = a[k * n + p];
          const akq = a[k * n + q];
          a[k * n + p] = c * akp - s * akq;
          a[k * n + q] = s * akp + c * akq;
        }
        for (let k = 0; k < n; k++) {
          const apk = a[p * n + k];
          const aqk = a[q * n + k];
          a[p * n + k] = c * apk - s * aqk;
          a[q * n + k] = s * apk + c * aqk;
        }
        for (let k = 0; k < n; k++) {
          const vkp = v[k * n + p];
          const vkq = v[k * n + q];
          v[k * n + p] = c * vkp - s * vkq;
          v[k * n + q] = s * vkp + c * vkq;
        }
      }
    }
  }
  const values = new Float64Array(n);
  for (let i = 0; i < n; i++) values[i] = a[i * n + i];
  return { values, vectors: v };
}

// Modified Gram-Schmidt orthonormalization of the columns of a rows x k matrix.
// Returns Q (rows x k) and the diagonal of R (for rank checks).
export function qrColumns(a, rows, k) {
  const q = new Float64Array(rows * k);
  const rdiag = new Float64Array(k);
  for (let j = 0; j < k; j++) {
    for (let i = 0; i < rows; i++) q[i * k + j] = a[i * k + j];
    for (let p = 0; p < j; p++) {
      let dot = 0;
      for (let i = 0; i < rows; i++) dot += q[i * k + p] * q[i * k + j];
      for (let i = 0; i < rows; i++) q[i * k + j] -= dot * q[i * k + p];
    }
    let norm = 0;
    for (let i = 0; i < rows; i++) norm += q[i * k + j] * q[i * k + j];
    norm = Math.sqrt(norm);
    rdiag[j] = norm;
    if (norm > 1e-12) for (let i = 0; i < rows; i++) q[i * k + j] /= norm;
  }
  return { q, rdiag };
}
