#pragma once

// Minimal FFT / window / statistics helpers used by the loop engine.
// numpy-compatible semantics; standard library only.
#include <complex>
#include <vector>

namespace loop_engine::detail {

using Cdp = std::complex<double>;

// In-place complex FFT. n must factor into 2s and 3s (e.g. 384, 2048).
// Matches numpy.fft: forward unscaled, inverse scaled by 1/n.
void fft(Cdp* data, int n, bool inverse);

// numpy.fft.rfft: n real samples -> n/2+1 spectrum bins.
void rfft(const double* time, int n, Cdp* spectrum);

// numpy.fft.irfft: n/2+1 spectrum bins -> n real samples.
void irfft(const Cdp* spectrum, int n, double* time);

// scipy.signal.get_window("hann", n, fftbins=True) — periodic Hann.
void hannPeriodic(int n, std::vector<double>& out);

// numpy.hanning(n) — symmetric Hann.
void hannSymmetric(int n, std::vector<double>& out);

// np.round: round half to even.
inline double rint(double x) { return std::nearbyint(x); }

// numpy.percentile (linear interpolation) over an ascending-sorted buffer.
double percentileLinear(const double* sortedAsc, int n, double q);

// numpy.median over an ascending-sorted buffer.
double medianOf(const double* sortedAsc, int n);

} // namespace loop_engine::detail
