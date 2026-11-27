// Shared internal types and helpers for the Lumen imaging core.
#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>
#include <thread>
#include <vector>

#include "lumen_core.h"

namespace lumen {

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float sqr(float v) { return v * v; }

// Single-channel float image.
struct Plane {
  int w = 0, h = 0;
  std::vector<float> px;
  Plane() = default;
  Plane(int w_, int h_, float v = 0.f) : w(w_), h(h_), px(size_t(w_) * size_t(h_), v) {}
  void reset(int w_, int h_, float v = 0.f) {
    w = w_;
    h = h_;
    px.assign(size_t(w_) * size_t(h_), v);
  }
  float* row(int y) { return px.data() + size_t(y) * w; }
  const float* row(int y) const { return px.data() + size_t(y) * w; }
  float& at(int x, int y) { return px[size_t(y) * w + x]; }
  float at(int x, int y) const { return px[size_t(y) * w + x]; }
  float atc(int x, int y) const { return px[size_t(clampi(y, 0, h - 1)) * w + clampi(x, 0, w - 1)]; }
  bool empty() const { return px.empty(); }
};

// Interleaved RGB float image (linear or display-referred depending on stage).
struct RGB {
  int w = 0, h = 0;
  std::vector<float> px;
  RGB() = default;
  RGB(int w_, int h_) : w(w_), h(h_), px(size_t(w_) * size_t(h_) * 3, 0.f) {}
  float* at(int x, int y) { return px.data() + (size_t(y) * w + x) * 3; }
  const float* at(int x, int y) const { return px.data() + (size_t(y) * w + x) * 3; }
};

// Splits [0, n) into contiguous chunks and runs them on all cores.
inline void parallel_for(int n, const std::function<void(int, int)>& fn, int min_chunk = 1) {
  if (n <= 0) return;
  unsigned hw = std::thread::hardware_concurrency();
  int threads = int(hw == 0 ? 4 : hw);
  threads = std::max(1, std::min(threads, (n + min_chunk - 1) / std::max(1, min_chunk)));
  if (threads == 1) {
    fn(0, n);
    return;
  }
  // Over-partition a bit for load balance.
  int chunks = std::min(n, threads * 4);
  std::atomic<int> next{0};
  auto worker = [&]() {
    for (;;) {
      int c = next.fetch_add(1);
      if (c >= chunks) break;
      int b = int((int64_t(n) * c) / chunks);
      int e = int((int64_t(n) * (c + 1)) / chunks);
      if (b < e) fn(b, e);
    }
  };
  std::vector<std::thread> pool;
  pool.reserve(threads - 1);
  for (int t = 0; t < threads - 1; ++t) pool.emplace_back(worker);
  worker();
  for (auto& t : pool) t.join();
}

// ---- 3x3 matrix helpers (row-major) ----------------------------------------
struct Mat3 {
  float m[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
  Mat3() = default;
  explicit Mat3(const float* a) { std::memcpy(m, a, sizeof(m)); }
  static Mat3 diag(float a, float b, float c) {
    Mat3 r;
    r.m[0] = a; r.m[4] = b; r.m[8] = c;
    r.m[1] = r.m[2] = r.m[3] = r.m[5] = r.m[6] = r.m[7] = 0.f;
    return r;
  }
  float operator()(int r, int c) const { return m[r * 3 + c]; }
  float& operator()(int r, int c) { return m[r * 3 + c]; }
  Mat3 operator*(const Mat3& o) const {
    Mat3 r;
    for (int i = 0; i < 3; ++i)
      for (int j = 0; j < 3; ++j) {
        double s = 0;
        for (int k = 0; k < 3; ++k) s += double(m[i * 3 + k]) * o.m[k * 3 + j];
        r.m[i * 3 + j] = float(s);
      }
    return r;
  }
  void apply(const float in[3], float out[3]) const {
    float a = in[0], b = in[1], c = in[2];
    out[0] = m[0] * a + m[1] * b + m[2] * c;
    out[1] = m[3] * a + m[4] * b + m[5] * c;
    out[2] = m[6] * a + m[7] * b + m[8] * c;
  }
  bool invert(Mat3& out) const {
    double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    double det = a * A + b * B + c * C;
    if (std::fabs(det) < 1e-12) return false;
    double inv = 1.0 / det;
    out.m[0] = float(A * inv);
    out.m[1] = float(-(b * i - c * h) * inv);
    out.m[2] = float((b * f - c * e) * inv);
    out.m[3] = float(B * inv);
    out.m[4] = float((a * i - c * g) * inv);
    out.m[5] = float(-(a * f - c * d) * inv);
    out.m[6] = float(C * inv);
    out.m[7] = float(-(a * h - b * g) * inv);
    out.m[8] = float((a * e - b * d) * inv);
    return true;
  }
  bool is_zero() const {
    for (float v : m)
      if (v != 0.f) return false;
    return true;
  }
};

// CFA helpers --------------------------------------------------------------
// Color (0=R,1=G,2=B) at each 2x2 position k for a CFA layout.
inline void cfa_colors(int cfa, int out[4]) {
  static const int table[4][4] = {
      {0, 1, 1, 2},  // RGGB
      {1, 0, 2, 1},  // GRBG
      {1, 2, 0, 1},  // GBRG
      {2, 1, 1, 0},  // BGGR
  };
  int c = clampi(cfa, 0, 3);
  for (int i = 0; i < 4; ++i) out[i] = table[c][i];
}

// Four half-resolution planes of a Bayer frame, normalized to [0,1].
struct BayerPlanes {
  int w = 0, h = 0;  // plane size (half of the frame)
  Plane p[4];
  void reset(int w_, int h_) {
    w = w_;
    h = h_;
    for (auto& q : p) q.reset(w_, h_);
  }
};

// Extracts normalized planes from uint16 raw data (row_stride in bytes).
void extract_planes(const LumenRawInfo& info, const uint16_t* data, BayerPlanes& out);
// Rebuilds a full-resolution float mosaic from planes.
void planes_to_mosaic(const BayerPlanes& planes, Plane& mosaic);
// Grayscale (mean of the 4 planes) at plane resolution.
void planes_to_gray(const BayerPlanes& planes, Plane& gray);
// Naive Quad-Bayer -> Bayer remosaic in place on a full-res uint16 copy.
void remosaic_quad_bayer(std::vector<uint16_t>& img, int w, int h);
// Unpacks MIPI RAW10 into uint16.
void unpack_raw10(const uint8_t* src, int w, int h, int stride, uint16_t* dst);

// Noise model estimation on normalized planes.
void estimate_noise_planes(const BayerPlanes& planes, float scale[4], float offset[4]);

// Separable blurs ------------------------------------------------------------
void gaussian_blur(const Plane& in, Plane& out, float sigma);
void box_blur(const Plane& in, Plane& out, int radius);
void downsample2(const Plane& in, Plane& out);  // [1 4 6 4 1] blur + decimate

float laplacian_variance(const Plane& g);

}  // namespace lumen
