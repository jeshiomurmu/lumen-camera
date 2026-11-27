// RAW plane handling, unpacking, blurs and noise-model estimation.
#include <algorithm>
#include <cmath>

#include "common.h"

namespace lumen {

void extract_planes(const LumenRawInfo& info, const uint16_t* data, BayerPlanes& out) {
  const int w2 = info.width / 2, h2 = info.height / 2;
  out.reset(w2, h2);
  const size_t stride = size_t(info.row_stride > 0 ? info.row_stride : info.width * 2) / 2;
  float scale[4], black[4];
  for (int k = 0; k < 4; ++k) {
    black[k] = info.black_level[k];
    float range = info.white_level - black[k];
    scale[k] = range > 1.f ? 1.f / range : 1.f;
  }
  parallel_for(h2, [&](int y0, int y1) {
    for (int j = y0; j < y1; ++j) {
      const uint16_t* r0 = data + size_t(2 * j) * stride;
      const uint16_t* r1 = data + size_t(2 * j + 1) * stride;
      float* p0 = out.p[0].row(j);
      float* p1 = out.p[1].row(j);
      float* p2 = out.p[2].row(j);
      float* p3 = out.p[3].row(j);
      for (int i = 0; i < w2; ++i) {
        p0[i] = (float(r0[2 * i]) - black[0]) * scale[0];
        p1[i] = (float(r0[2 * i + 1]) - black[1]) * scale[1];
        p2[i] = (float(r1[2 * i]) - black[2]) * scale[2];
        p3[i] = (float(r1[2 * i + 1]) - black[3]) * scale[3];
      }
    }
  }, 16);
}

void planes_to_mosaic(const BayerPlanes& planes, Plane& mosaic) {
  mosaic.reset(planes.w * 2, planes.h * 2);
  parallel_for(planes.h, [&](int y0, int y1) {
    for (int j = y0; j < y1; ++j) {
      float* m0 = mosaic.row(2 * j);
      float* m1 = mosaic.row(2 * j + 1);
      const float* p0 = planes.p[0].row(j);
      const float* p1 = planes.p[1].row(j);
      const float* p2 = planes.p[2].row(j);
      const float* p3 = planes.p[3].row(j);
      for (int i = 0; i < planes.w; ++i) {
        m0[2 * i] = p0[i];
        m0[2 * i + 1] = p1[i];
        m1[2 * i] = p2[i];
        m1[2 * i + 1] = p3[i];
      }
    }
  }, 16);
}

void planes_to_gray(const BayerPlanes& planes, Plane& gray) {
  gray.reset(planes.w, planes.h);
  parallel_for(planes.h, [&](int y0, int y1) {
    for (int j = y0; j < y1; ++j) {
      float* g = gray.row(j);
      const float* p0 = planes.p[0].row(j);
      const float* p1 = planes.p[1].row(j);
      const float* p2 = planes.p[2].row(j);
      const float* p3 = planes.p[3].row(j);
      for (int i = 0; i < planes.w; ++i) g[i] = 0.25f * (p0[i] + p1[i] + p2[i] + p3[i]);
    }
  }, 16);
}

// Quad Bayer (each color covers a 2x2 cell) -> Bayer by swapping column 1<->2
// and row 1<->2 inside every 4x4 tile. Cheap "naive remosaic".
void remosaic_quad_bayer(std::vector<uint16_t>& img, int w, int h) {
  parallel_for(h / 4, [&](int t0, int t1) {
    for (int t = t0; t < t1; ++t) {
      int y = t * 4;
      for (int yy = y; yy < y + 4; ++yy) {
        uint16_t* r = img.data() + size_t(yy) * w;
        for (int x = 0; x + 3 < w; x += 4) std::swap(r[x + 1], r[x + 2]);
      }
      uint16_t* r1 = img.data() + size_t(y + 1) * w;
      uint16_t* r2 = img.data() + size_t(y + 2) * w;
      std::swap_ranges(r1, r1 + w, r2);
    }
  }, 4);
}

void unpack_raw10(const uint8_t* src, int w, int h, int stride, uint16_t* dst) {
  parallel_for(h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      const uint8_t* s = src + size_t(y) * stride;
      uint16_t* d = dst + size_t(y) * w;
      for (int x = 0; x + 3 < w; x += 4, s += 5) {
        uint8_t lo = s[4];
        d[x + 0] = uint16_t((s[0] << 2) | (lo & 3));
        d[x + 1] = uint16_t((s[1] << 2) | ((lo >> 2) & 3));
        d[x + 2] = uint16_t((s[2] << 2) | ((lo >> 4) & 3));
        d[x + 3] = uint16_t((s[3] << 2) | ((lo >> 6) & 3));
      }
    }
  }, 8);
}

// ---------------------------------------------------------------------------
static void make_gauss_kernel(float sigma, std::vector<float>& k) {
  int r = std::max(1, int(std::ceil(sigma * 3.f)));
  k.resize(size_t(2 * r + 1));
  float s = 0;
  for (int i = -r; i <= r; ++i) {
    float v = std::exp(-0.5f * float(i * i) / (sigma * sigma));
    k[size_t(i + r)] = v;
    s += v;
  }
  for (auto& v : k) v /= s;
}

static void convolve_separable(const Plane& in, Plane& out, const std::vector<float>& k) {
  const int r = int(k.size() / 2);
  Plane tmp(in.w, in.h);
  parallel_for(in.h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      const float* s = in.row(y);
      float* d = tmp.row(y);
      for (int x = 0; x < in.w; ++x) {
        float acc = 0;
        if (x >= r && x + r < in.w) {
          for (int i = -r; i <= r; ++i) acc += k[size_t(i + r)] * s[x + i];
        } else {
          for (int i = -r; i <= r; ++i) acc += k[size_t(i + r)] * s[clampi(x + i, 0, in.w - 1)];
        }
        d[x] = acc;
      }
    }
  }, 8);
  out.reset(in.w, in.h);
  parallel_for(in.h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      float* d = out.row(y);
      for (int x = 0; x < in.w; ++x) d[x] = 0;
      for (int i = -r; i <= r; ++i) {
        const float* s = tmp.row(clampi(y + i, 0, in.h - 1));
        float kv = k[size_t(i + r)];
        for (int x = 0; x < in.w; ++x) d[x] += kv * s[x];
      }
    }
  }, 8);
}

void gaussian_blur(const Plane& in, Plane& out, float sigma) {
  if (sigma <= 0.05f) {
    out = in;
    return;
  }
  std::vector<float> k;
  make_gauss_kernel(sigma, k);
  convolve_separable(in, out, k);
}

void box_blur(const Plane& in, Plane& out, int radius) {
  if (radius <= 0) {
    out = in;
    return;
  }
  std::vector<float> k(size_t(2 * radius + 1), 1.f / float(2 * radius + 1));
  convolve_separable(in, out, k);
}

void downsample2(const Plane& in, Plane& out) {
  static const std::vector<float> k = {1.f / 16, 4.f / 16, 6.f / 16, 4.f / 16, 1.f / 16};
  Plane blurred;
  convolve_separable(in, blurred, k);
  const int w = (in.w + 1) / 2, h = (in.h + 1) / 2;
  out.reset(w, h);
  for (int y = 0; y < h; ++y) {
    const float* s = blurred.row(std::min(2 * y, in.h - 1));
    float* d = out.row(y);
    for (int x = 0; x < w; ++x) d[x] = s[std::min(2 * x, in.w - 1)];
  }
}

float laplacian_variance(const Plane& g) {
  if (g.w < 3 || g.h < 3) return 0.f;
  double s = 0, s2 = 0;
  size_t n = 0;
  for (int y = 1; y < g.h - 1; y += 2) {
    const float* r = g.row(y);
    const float* u = g.row(y - 1);
    const float* d = g.row(y + 1);
    for (int x = 1; x < g.w - 1; x += 2) {
      double l = 4.0 * r[x] - r[x - 1] - r[x + 1] - u[x] - d[x];
      s += l;
      s2 += l * l;
      ++n;
    }
  }
  if (n == 0) return 0.f;
  double m = s / double(n);
  return float(s2 / double(n) - m * m);
}

// ---------------------------------------------------------------------------
// Noise estimation: residual of a 4-neighbour predictor inside each plane,
// measured in 8x8 blocks; the low percentile per intensity bin rejects
// texture. Fits variance = S*x + O by weighted least squares.
static void estimate_noise_plane(const Plane& p, float& S, float& O) {
  const int B = 8;
  const int bx = (p.w - 2) / B, by = (p.h - 2) / B;
  struct Blk {
    float mean, var;
  };
  std::vector<Blk> blocks;
  blocks.reserve(size_t(std::max(0, bx * by)));
  for (int j = 0; j < by; ++j) {
    for (int i = 0; i < bx; ++i) {
      double m = 0, r1 = 0, r2 = 0;
      int n = 0;
      for (int y = 1 + j * B; y < 1 + (j + 1) * B; ++y) {
        const float* row = p.row(y);
        const float* up = p.row(y - 1);
        const float* dn = p.row(y + 1);
        for (int x = 1 + i * B; x < 1 + (i + 1) * B; ++x) {
          float v = row[x];
          float res = v - 0.25f * (row[x - 1] + row[x + 1] + up[x] + dn[x]);
          m += v;
          r1 += res;
          r2 += double(res) * res;
          ++n;
        }
      }
      m /= n;
      double var = r2 / n - (r1 / n) * (r1 / n);
      if (m < 0.92) blocks.push_back({float(m), float(var / 1.25)});
    }
  }
  if (blocks.size() < 16) {
    S = 0.f;
    O = 1e-6f;
    return;
  }
  float maxm = 0;
  for (auto& b : blocks) maxm = std::max(maxm, b.mean);
  const int NB = 24;
  std::vector<std::vector<float>> bins(NB);
  std::vector<double> binMean(NB, 0.0);
  for (auto& b : blocks) {
    float t = clampf(b.mean / std::max(1e-6f, maxm), 0.f, 0.9999f);
    int k = int(t * NB);
    bins[size_t(k)].push_back(b.var);
    binMean[size_t(k)] += b.mean;
  }
  // Low percentile per bin (Wilson-Hilferty bias factor for 64-sample
  // variances), then an iteratively re-weighted line fit that drops bins
  // lying well above the lower envelope (textured bins).
  const float pct = 0.1f, bias = 0.78f;
  struct BinPt { double x, y, w; };
  std::vector<BinPt> pts;
  for (int k = 0; k < NB; ++k) {
    auto& v = bins[size_t(k)];
    if (v.size() < 12) continue;
    double mx = binMean[size_t(k)] / double(v.size());
    size_t idx = size_t(float(v.size() - 1) * pct);
    std::nth_element(v.begin(), v.begin() + long(idx), v.end());
    pts.push_back({mx, double(v[idx]) / bias, double(v.size())});
  }
  if (pts.size() < 2) {
    S = 0.f;
    O = pts.empty() ? 1e-6f : float(pts[0].y);
    return;
  }
  double slope = 0, icpt = 0;
  std::vector<bool> use(pts.size(), true);
  for (int iter = 0; iter < 4; ++iter) {
    double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
    int used = 0;
    for (size_t i = 0; i < pts.size(); ++i) {
      if (!use[i]) continue;
      const double w = pts[i].w;
      sw += w; sx += w * pts[i].x; sy += w * pts[i].y;
      sxx += w * pts[i].x * pts[i].x; sxy += w * pts[i].x * pts[i].y;
      ++used;
    }
    if (used < 2 || sw <= 0) break;
    const double den = sw * sxx - sx * sx;
    slope = den != 0 ? (sw * sxy - sx * sy) / den : 0.0;
    icpt = (sy - slope * sx) / sw;
    if (slope < 0) { slope = 0; icpt = sy / sw; }
    bool changed = false;
    for (size_t i = 0; i < pts.size(); ++i) {
      const double fit = std::max(1e-12, slope * pts[i].x + icpt);
      const bool keep = pts[i].y < 1.35 * fit;
      if (keep != use[i]) { use[i] = keep; changed = true; }
    }
    if (!changed) break;
  }
  S = float(std::max(slope, 1e-8));
  O = float(std::max(icpt, 1e-10));
}

void estimate_noise_planes(const BayerPlanes& planes, float scale[4], float offset[4]) {
  for (int k = 0; k < 4; ++k) estimate_noise_plane(planes.p[k], scale[k], offset[k]);
}

}  // namespace lumen
