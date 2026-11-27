// Tiny radix-2 complex FFT specialised for 16x16 tiles (burst merge, NR).
#pragma once
#include <cmath>
#include <utility>

namespace lumen {

struct FFT16 {
  float cosT[8], sinT[8];
  int rev[16];
  float win[256];      // separable raised-cosine window (sums to 1 at 50% overlap)
  float win2sum = 0;   // sum of window^2 (noise power scale of a windowed tile)

  FFT16() {
    const double PI = 3.14159265358979323846;
    for (int k = 0; k < 8; ++k) {
      cosT[k] = float(std::cos(2.0 * PI * k / 16.0));
      sinT[k] = float(std::sin(2.0 * PI * k / 16.0));
    }
    for (int i = 0; i < 16; ++i) {
      int r = 0;
      for (int b = 0; b < 4; ++b)
        if (i & (1 << b)) r |= 1 << (3 - b);
      rev[i] = r;
    }
    float w1[16];
    for (int i = 0; i < 16; ++i) w1[i] = float(0.5 - 0.5 * std::cos(2.0 * PI * (i + 0.5) / 16.0));
    win2sum = 0;
    for (int y = 0; y < 16; ++y)
      for (int x = 0; x < 16; ++x) {
        win[y * 16 + x] = w1[x] * w1[y];
        win2sum += win[y * 16 + x] * win[y * 16 + x];
      }
  }

  // 16-point FFT on a strided vector. inverse => +i twiddles (no scaling).
  void run(float* re, float* im, int stride, bool inverse) const {
    for (int i = 0; i < 16; ++i) {
      int j = rev[i] & 15;
      if (j > i) {
        std::swap(re[i * stride], re[j * stride]);
        std::swap(im[i * stride], im[j * stride]);
      }
    }
    for (int len = 2; len <= 16; len <<= 1) {
      const int half = len >> 1, step = 16 / len;
      for (int i = 0; i < 16; i += len) {
        for (int k = 0; k < half; ++k) {
          const float wr = cosT[k * step];
          const float wi = inverse ? sinT[k * step] : -sinT[k * step];
          const int a = (i + k) * stride, b = (i + k + half) * stride;
          const float tr = re[b] * wr - im[b] * wi;
          const float ti = re[b] * wi + im[b] * wr;
          re[b] = re[a] - tr;
          im[b] = im[a] - ti;
          re[a] += tr;
          im[a] += ti;
        }
      }
    }
  }

  void fft2d(float* re, float* im, bool inverse) const {
    for (int r = 0; r < 16; ++r) run(re + r * 16, im + r * 16, 1, inverse);
    for (int c = 0; c < 16; ++c) run(re + c, im + c, 16, inverse);
  }
};

inline const FFT16& fft16() {
  static const FFT16 f;
  return f;
}

}  // namespace lumen
