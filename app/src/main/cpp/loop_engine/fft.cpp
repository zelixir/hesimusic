#include "fft.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace loop_engine::detail {
namespace {

constexpr double kTwoPi = 6.283185307179586476925286766559;

inline bool isPow2(int n) { return n > 0 && (n & (n - 1)) == 0; }

void fftPow2(Cdp* a, int n, bool inverse, bool scale) {
    for (int i = 1, j = 0; i < n; i++) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (int len = 2; len <= n; len <<= 1) {
        const double ang = (inverse ? kTwoPi : -kTwoPi) / double(len);
        const Cdp wl(std::cos(ang), std::sin(ang));
        const int half = len >> 1;
        for (int i = 0; i < n; i += len) {
            Cdp w(1.0, 0.0);
            for (int k = 0; k < half; k++) {
                const Cdp u = a[i + k];
                const Cdp v = a[i + k + half] * w;
                a[i + k] = u + v;
                a[i + k + half] = u - v;
                w *= wl;
            }
        }
    }
    if (scale && inverse) {
        const double s = 1.0 / double(n);
        for (int i = 0; i < n; i++) a[i] *= s;
    }
}

// Recursive Cooley-Tukey for n whose prime factors are 2 and 3.
// x: input viewed with element stride; y: output buffer of size n.
void fftRec(const Cdp* x, Cdp* y, int n, int stride, bool inverse, Cdp* scratch) {
    if (n == 1) {
        y[0] = x[0];
        return;
    }
    if (isPow2(n)) {
        for (int i = 0; i < n; i++) y[i] = x[size_t(i) * size_t(stride)];
        fftPow2(y, n, inverse, false);
        return;
    }
    int p;
    if (n % 3 == 0 && isPow2(n / 3)) p = 3;
    else if (n % 2 == 0) p = 2;
    else if (n % 3 == 0) p = 3;
    else p = n; // small prime: direct DFT
    const int m = n / p;
    for (int r = 0; r < p; r++)
        fftRec(x + size_t(r) * size_t(stride), y + size_t(r) * size_t(m), m,
               stride * p, inverse, scratch);
    // X[k] = sum_r w_n^{r k} * Y_r[k mod m]
    for (int k = 0; k < n; k++) {
        Cdp acc(0.0, 0.0);
        const int km = k % m;
        for (int r = 0; r < p; r++) {
            const double ang = (inverse ? kTwoPi : -kTwoPi) * double(r) * double(k) / double(n);
            acc += y[size_t(r) * size_t(m) + size_t(km)] * Cdp(std::cos(ang), std::sin(ang));
        }
        scratch[k] = acc;
    }
    for (int k = 0; k < n; k++) y[k] = scratch[k];
}

} // namespace

void fft(Cdp* data, int n, bool inverse) {
    if (n <= 1) return;
    if (isPow2(n)) {
        fftPow2(data, n, inverse, true);
        return;
    }
    std::vector<Cdp> buf(n), scratch(n);
    fftRec(data, buf.data(), n, 1, inverse, scratch.data());
    if (inverse) {
        const double s = 1.0 / double(n);
        for (int i = 0; i < n; i++) data[i] = buf[i] * s;
    } else {
        std::copy(buf.begin(), buf.end(), data);
    }
}

void rfft(const double* time, int n, Cdp* spectrum) {
    std::vector<Cdp> buf(n);
    for (int i = 0; i < n; i++) buf[size_t(i)] = Cdp(time[i], 0.0);
    fft(buf.data(), n, false);
    for (int i = 0; i <= n / 2; i++) spectrum[i] = buf[size_t(i)];
}

void irfft(const Cdp* spectrum, int n, double* time) {
    std::vector<Cdp> buf(n);
    buf[0] = Cdp(spectrum[0].real(), 0.0);
    for (int k = 1; k <= n / 2; k++) buf[size_t(k)] = spectrum[k];
    for (int k = n / 2 + 1; k < n; k++) buf[size_t(k)] = std::conj(spectrum[n - k]);
    fft(buf.data(), n, true);
    for (int i = 0; i < n; i++) time[i] = buf[size_t(i)].real();
}

void hannPeriodic(int n, std::vector<double>& out) {
    out.resize(size_t(n));
    for (int i = 0; i < n; i++)
        out[size_t(i)] = 0.5 - 0.5 * std::cos(kTwoPi * double(i) / double(n));
}

void hannSymmetric(int n, std::vector<double>& out) {
    out.resize(size_t(n));
    const double denom = double(n - 1);
    for (int i = 0; i < n; i++)
        out[size_t(i)] = 0.5 - 0.5 * std::cos(kTwoPi * double(i) / denom);
}

double percentileLinear(const double* s, int n, double q) {
    if (n <= 0) return std::numeric_limits<double>::quiet_NaN();
    if (n == 1) return s[0];
    const double pos = (q / 100.0) * double(n - 1);
    const int lo = int(std::floor(pos));
    int hi = int(std::ceil(pos));
    if (hi >= n) hi = n - 1;
    const double frac = pos - double(lo);
    return s[lo] + (s[hi] - s[lo]) * frac;
}

double medianOf(const double* s, int n) {
    if (n <= 0) return std::numeric_limits<double>::quiet_NaN();
    if (n % 2 == 1) return s[n / 2];
    return 0.5 * (s[n / 2 - 1] + s[n / 2]);
}

} // namespace loop_engine::detail
