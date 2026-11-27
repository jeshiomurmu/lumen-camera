// Multi-frame merge of processed YUV 4:2:0 frames (phones without RAW access).
//
// The frames come out of the phone's own ISP, so colour, white balance and tone are
// already the device's. This merge only removes temporal noise: it aligns every frame
// to the first one (coarse-to-fine tile search on the luma pyramid), then averages
// each 2x2 block with a weight that falls to zero where the aligned frame disagrees
// with the reference by more than the measured noise (motion, misalignment). Nothing
// is sharpened, tone mapped or synthesised, so textures stay real.
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

#include "common.h"
#include "lumen_core.h"

namespace lumen {
namespace {

struct Img8 {
  int w = 0, h = 0;
  std::vector<uint8_t> px;
  void resize(int w_, int h_) { w = w_; h = h_; px.assign(size_t(w) * h, 0); }
  uint8_t at(int x, int y) const {
    x = x < 0 ? 0 : (x >= w ? w - 1 : x);
    y = y < 0 ? 0 : (y >= h ? h - 1 : y);
    return px[size_t(y) * w + x];
  }
  const uint8_t* row(int y) const { return px.data() + size_t(y) * w; }
};

void downsample(const Img8& s, Img8& d) {
  d.resize(s.w / 2, s.h / 2);
  parallel_for(d.h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      const uint8_t* a = s.row(2 * y);
      const uint8_t* b = s.row(2 * y + 1);
      uint8_t* o = d.px.data() + size_t(y) * d.w;
      for (int x = 0; x < d.w; ++x) o[x] = uint8_t((a[2 * x] + a[2 * x + 1] + b[2 * x] + b[2 * x + 1] + 2) >> 2);
    }
  }, 16);
}

// Sum of absolute differences of a tile of `ref` against `cur` displaced by (dx, dy).
uint32_t sad(const Img8& ref, const Img8& cur, int x0, int y0, int tw, int th, int dx, int dy, int step) {
  uint32_t s = 0;
  for (int y = y0; y < y0 + th; y += step) {
    if (y >= ref.h) break;
    const uint8_t* r = ref.row(y);
    int cy = y + dy;
    bool rowIn = cy >= 0 && cy < cur.h;
    for (int x = x0; x < x0 + tw; x += step) {
      if (x >= ref.w) break;
      int cx = x + dx;
      int c = (rowIn && cx >= 0 && cx < cur.w) ? cur.row(cy)[cx] : cur.at(cx, cy);
      s += uint32_t(std::abs(int(r[x]) - c));
    }
  }
  return s;
}

struct Pyr { Img8 l[4]; };

void build(Pyr& p) { for (int i = 1; i < 4; ++i) downsample(p.l[i - 1], p.l[i]); }

}  // namespace
}  // namespace lumen

using namespace lumen;

struct LumenYuvMerge {
  int w = 0, h = 0, bw = 0, bh = 0;
  float strength = 1.f;
  Pyr ref, cur;
  Img8 refU, refV, curU, curV;
  std::vector<float> accY, accU, accV, wB;
  float sigma = -1.f;   // temporal luma noise (8-bit levels), measured on the 2nd frame
  int frames = 0;
  double weightSum = 0;  // mean weight of non-reference frames, for stats
  int gdx = 0, gdy = 0;
  static constexpr int T = 64;  // tile size at full resolution
};

static void copy_frame(LumenYuvMerge* m, const uint8_t* y, int ys, const uint8_t* u, const uint8_t* v, int uvs, int uvps,
                       Img8& Y, Img8& U, Img8& V) {
  Y.resize(m->w, m->h);
  U.resize(m->bw, m->bh);
  V.resize(m->bw, m->bh);
  parallel_for(m->h, [&](int y0, int y1) {
    for (int r = y0; r < y1; ++r) std::memcpy(Y.px.data() + size_t(r) * m->w, y + size_t(r) * ys, size_t(m->w));
  }, 64);
  parallel_for(m->bh, [&](int y0, int y1) {
    for (int r = y0; r < y1; ++r) {
      const uint8_t* su = u + size_t(r) * uvs;
      const uint8_t* sv = v + size_t(r) * uvs;
      uint8_t* du = U.px.data() + size_t(r) * m->bw;
      uint8_t* dv = V.px.data() + size_t(r) * m->bw;
      if (uvps == 1) {
        std::memcpy(du, su, size_t(m->bw));
        std::memcpy(dv, sv, size_t(m->bw));
      } else {
        for (int x = 0; x < m->bw; ++x) { du[x] = su[x * uvps]; dv[x] = sv[x * uvps]; }
      }
    }
  }, 32);
}

extern "C" {

LumenYuvMerge* lumen_yuv_merge_create(int32_t width, int32_t height, float strength) {
  if (width < 64 || height < 64) return nullptr;
  auto* m = new (std::nothrow) LumenYuvMerge();
  if (!m) return nullptr;
  m->w = width & ~1;
  m->h = height & ~1;
  m->bw = m->w / 2;
  m->bh = m->h / 2;
  m->strength = strength > 0.f ? strength : 1.f;
  return m;
}

int32_t lumen_yuv_merge_add(LumenYuvMerge* m, const uint8_t* y, int32_t y_row_stride, const uint8_t* u, const uint8_t* v,
                            int32_t uv_row_stride, int32_t uv_pixel_stride) {
  if (!m || !y || !u || !v || y_row_stride < m->w || uv_pixel_stride < 1) return LUMEN_ERR_INVALID_ARG;
  const int W = m->w, H = m->h, BW = m->bw, BH = m->bh;
  if (m->frames == 0) {
    copy_frame(m, y, y_row_stride, u, v, uv_row_stride, uv_pixel_stride, m->ref.l[0], m->refU, m->refV);
    build(m->ref);
    try {
      m->accY.resize(size_t(W) * H);
      m->accU.resize(size_t(BW) * BH);
      m->accV.resize(size_t(BW) * BH);
      m->wB.assign(size_t(BW) * BH, 1.f);
    } catch (...) {
      return LUMEN_ERR_NO_MEMORY;
    }
    for (size_t i = 0; i < m->accY.size(); ++i) m->accY[i] = m->ref.l[0].px[i];
    for (size_t i = 0; i < m->accU.size(); ++i) { m->accU[i] = m->refU.px[i]; m->accV[i] = m->refV.px[i]; }
    m->frames = 1;
    return LUMEN_OK;
  }
  copy_frame(m, y, y_row_stride, u, v, uv_row_stride, uv_pixel_stride, m->cur.l[0], m->curU, m->curV);
  build(m->cur);

  // 1) global shift: exhaustive search at 1/8 resolution (+-10 px there = +-80 px), then the
  //    best few candidates are refined at 1/4 and 1/2 resolution on the whole frame. Keeping
  //    several candidates avoids locking onto a repeated pattern (tiles, windows, fences)
  //    because of the coarse 8-px quantisation.
  {
    const Img8& r3 = m->ref.l[3];
    const Img8& c3 = m->cur.l[3];
    const int R = 10, D = 2 * R + 1;
    const int mx = r3.w / 10, my = r3.h / 10;
    std::vector<uint32_t> cost(size_t(D) * D);
    parallel_for(D, [&](int a, int b) {
      for (int j = a; j < b; ++j)
        for (int i = 0; i < D; ++i)
          cost[size_t(j) * D + i] = sad(r3, c3, mx, my, r3.w - 2 * mx, r3.h - 2 * my, i - R, j - R, 1);
    });
    // local minima, best first
    std::vector<std::pair<uint32_t, int>> cand;
    for (int j = 0; j < D; ++j)
      for (int i = 0; i < D; ++i) {
        uint32_t c = cost[size_t(j) * D + i];
        bool isMin = true;
        for (int v = -1; v <= 1 && isMin; ++v)
          for (int u = -1; u <= 1; ++u) {
            int ii = i + u, jj = j + v;
            if ((u || v) && ii >= 0 && jj >= 0 && ii < D && jj < D && cost[size_t(jj) * D + ii] < c) { isMin = false; break; }
          }
        if (isMin) cand.push_back({c, j * D + i});
      }
    std::sort(cand.begin(), cand.end());
    if (cand.size() > 4) cand.resize(4);
    auto refine = [&](int L, int R2, int& dx, int& dy) {
      const Img8& rl = m->ref.l[L];
      const Img8& cl = m->cur.l[L];
      const int ex = rl.w / 10, ey = rl.h / 10, n = 2 * R2 + 1;
      std::vector<uint32_t> c(size_t(n) * n);
      parallel_for(n, [&](int a, int b) {
        for (int j = a; j < b; ++j)
          for (int i = 0; i < n; ++i)
            c[size_t(j) * n + i] = sad(rl, cl, ex, ey, rl.w - 2 * ex, rl.h - 2 * ey, dx + i - R2, dy + j - R2, 2);
      });
      size_t k = size_t(std::min_element(c.begin(), c.end()) - c.begin());
      dx += int(k % n) - R2;
      dy += int(k / n) - R2;
      return c[k];
    };
    uint32_t bestCost = UINT32_MAX;
    int bdx = 0, bdy = 0;
    for (auto& cd : cand) {
      int dx = (cd.second % D - R) * 2, dy = (cd.second / D - R) * 2;  // to 1/4 resolution
      uint32_t c = refine(2, 2, dx, dy);
      // tiny preference for the smaller shift when two candidates are equally good
      uint64_t pc = uint64_t(c) * (1000 + std::abs(dx) + std::abs(dy)) / 1000;
      if (pc < bestCost) { bestCost = uint32_t(std::min<uint64_t>(pc, UINT32_MAX - 1)); bdx = dx; bdy = dy; }
    }
    bdx *= 2; bdy *= 2;
    refine(1, 1, bdx, bdy);
    m->gdx = bdx * 2;
    m->gdy = bdy * 2;
  }

  // 2) per-tile refinement: 1/4 (+-3), 1/2 (+-1), full (+-1)
  const int T = LumenYuvMerge::T;
  const int tw = (W + T - 1) / T, th = (H + T - 1) / T;
  std::vector<int> tdx(size_t(tw) * th), tdy(size_t(tw) * th);
  parallel_for(th, [&](int a, int b) {
    for (int ty = a; ty < b; ++ty)
      for (int tx = 0; tx < tw; ++tx) {
        int dx = m->gdx / 4, dy = m->gdy / 4;
        struct Lv { int level, radius, step; };
        const Lv lv[3] = {{2, 2, 1}, {1, 1, 1}, {0, 1, 2}};
        for (int k = 0; k < 3; ++k) {
          const int L = lv[k].level, s = 1 << L, R = lv[k].radius;
          if (k > 0) { dx *= 2; dy *= 2; }
          const Img8& rl = m->ref.l[L];
          const Img8& cl = m->cur.l[L];
          int x0 = tx * T / s, y0 = ty * T / s, ts = T / s;
          uint32_t bestC = UINT32_MAX;
          int bx = dx, by = dy;
          for (int j = -R; j <= R; ++j)
            for (int i = -R; i <= R; ++i) {
              uint32_t c = sad(rl, cl, x0, y0, ts, ts, dx + i, dy + j, lv[k].step);
              // prefer the coarser estimate on ties (flat areas)
              if (c < bestC || (c == bestC && i == 0 && j == 0)) { bestC = c; bx = dx + i; by = dy + j; }
            }
          dx = bx; dy = by;
        }
        tdx[size_t(ty) * tw + tx] = dx;
        tdy[size_t(ty) * tw + tx] = dy;
      }
  });

  // displacement of a 2x2 block (full-res pixels), bilinear between tile centres
  auto disp = [&](int bx, int by, int& dx, int& dy) {
    float fx = (2.f * bx + 1.f) / T - 0.5f, fy = (2.f * by + 1.f) / T - 0.5f;
    int x0 = std::clamp(int(std::floor(fx)), 0, tw - 1), y0 = std::clamp(int(std::floor(fy)), 0, th - 1);
    int x1 = std::min(x0 + 1, tw - 1), y1 = std::min(y0 + 1, th - 1);
    float ax = clampf(fx - x0, 0.f, 1.f), ay = clampf(fy - y0, 0.f, 1.f);
    auto lerp = [&](const std::vector<int>& t) {
      float top = t[size_t(y0) * tw + x0] * (1 - ax) + t[size_t(y0) * tw + x1] * ax;
      float bot = t[size_t(y1) * tw + x0] * (1 - ax) + t[size_t(y1) * tw + x1] * ax;
      return top * (1 - ay) + bot * ay;
    };
    dx = int(std::lround(lerp(tdx)));
    dy = int(std::lround(lerp(tdy)));
  };

  const Img8& R0 = m->ref.l[0];
  const Img8& C0 = m->cur.l[0];

  // 3) noise level from the first pair: robust spread of the aligned temporal difference
  if (m->sigma < 0.f) {
    std::vector<uint8_t> d;
    d.reserve(size_t(BW / 4 + 1) * (BH / 4 + 1));
    for (int by = 2; by < BH - 2; by += 4)
      for (int bx = 2; bx < BW - 2; bx += 4) {
        int dx, dy;
        disp(bx, by, dx, dy);
        int x = 2 * bx, yy = 2 * by;
        d.push_back(uint8_t(std::min(255, std::abs(int(R0.at(x, yy)) - int(C0.at(x + dx, yy + dy))))));
      }
    if (d.empty()) return LUMEN_ERR_STATE;
    std::nth_element(d.begin(), d.begin() + d.size() / 2, d.end());
    float med = float(d[d.size() / 2]);
    // |N(0, s*sqrt2)| has median 0.6745*s*sqrt2; integer data floors at ~0.5 level
    m->sigma = std::max(0.6f, (med + 0.5f) / (0.6745f * 1.41421f));
  }
  // block statistic: mean |diff| over a 4x4 window. For pure noise its mean is 1.128*sigma
  // and its spread ~0.21*sigma; accept fully up to +2.5 spreads, reject by +7.
  const float s = m->sigma;
  const float t0 = 1.128f * s + 2.5f * 0.213f * s * m->strength;
  const float t1 = t0 + 1.5f * s * m->strength;
  const float t0c = 1.128f * s + 2.5f * 0.426f * s * m->strength;  // same rule for 4 samples
  const float t1c = t0c + 2.0f * s * m->strength;

  double wsum = 0;
  std::vector<double> rowW(size_t(BH), 0.0);
  parallel_for(BH, [&](int a, int b) {
    for (int by = a; by < b; ++by) {
      double rw = 0;
      for (int bx = 0; bx < BW; ++bx) {
        int dx, dy;
        disp(bx, by, dx, dy);
        const int x = 2 * bx, yy = 2 * by;
        int acc = 0, core = 0;
        for (int j = -1; j <= 2; ++j)
          for (int i = -1; i <= 2; ++i) {
            int d = std::abs(int(R0.at(x + i, yy + j)) - int(C0.at(x + i + dx, yy + j + dy)));
            acc += d;
            if (i >= 0 && i <= 1 && j >= 0 && j <= 1) core += d;
          }
        float dl = acc / 16.f;
        // the 2x2 pixels actually merged must agree too (object edges inside the window)
        float wcore = clampf((t1c - core * 0.25f) / (t1c - t0c), 0.f, 1.f);
        const int cdx = (dx >= 0 ? dx + 1 : dx - 1) / 2, cdy = (dy >= 0 ? dy + 1 : dy - 1) / 2;
        const int cu = m->curU.at(bx + cdx, by + cdy), cv = m->curV.at(bx + cdx, by + cdy);
        const size_t bi = size_t(by) * BW + bx;
        float dc = 0.5f * (std::abs(int(m->refU.px[bi]) - cu) + std::abs(int(m->refV.px[bi]) - cv));
        float dist = std::max(dl, dc);
        float wgt = std::min(wcore, clampf((t1 - dist) / (t1 - t0), 0.f, 1.f));
        if (wgt <= 0.f) continue;
        rw += wgt;
        for (int j = 0; j < 2; ++j)
          for (int i = 0; i < 2; ++i) m->accY[size_t(yy + j) * W + x + i] += wgt * C0.at(x + i + dx, yy + j + dy);
        m->accU[bi] += wgt * cu;
        m->accV[bi] += wgt * cv;
        m->wB[bi] += wgt;
      }
      rowW[by] = rw;
    }
  }, 8);
  for (double v : rowW) wsum += v;
  m->weightSum += wsum / (double(BW) * BH);
  m->frames++;
  return LUMEN_OK;
}

int32_t lumen_yuv_merge_render(LumenYuvMerge* m, uint8_t* rgba, int32_t stride, LumenYuvMergeStats* stats) {
  if (!m || !rgba || stride < m->w * 4) return LUMEN_ERR_INVALID_ARG;
  if (m->frames == 0) return LUMEN_ERR_STATE;
  const int W = m->w, H = m->h, BW = m->bw, BH = m->bh;
  std::vector<float> U(size_t(BW) * BH), V(size_t(BW) * BH);
  for (size_t i = 0; i < U.size(); ++i) { U[i] = m->accU[i] / m->wB[i] - 128.f; V[i] = m->accV[i] / m->wB[i] - 128.f; }
  parallel_for(H, [&](int a, int b) {
    for (int y = a; y < b; ++y) {
      uint8_t* o = rgba + size_t(y) * stride;
      // chroma sample centres sit between luma rows/cols: bilinear upsample
      float fy = (y - 0.5f) * 0.5f;
      int y0 = std::clamp(int(std::floor(fy)), 0, BH - 1), y1 = std::min(y0 + 1, BH - 1);
      float ay = clampf(fy - y0, 0.f, 1.f);
      const int by = y >> 1;
      for (int x = 0; x < W; ++x) {
        float fx = (x - 0.5f) * 0.5f;
        int x0 = std::clamp(int(std::floor(fx)), 0, BW - 1), x1 = std::min(x0 + 1, BW - 1);
        float ax = clampf(fx - x0, 0.f, 1.f);
        auto bil = [&](const std::vector<float>& p) {
          float t = p[size_t(y0) * BW + x0] * (1 - ax) + p[size_t(y0) * BW + x1] * ax;
          float bt = p[size_t(y1) * BW + x0] * (1 - ax) + p[size_t(y1) * BW + x1] * ax;
          return t * (1 - ay) + bt * ay;
        };
        float Y = m->accY[size_t(y) * W + x] / m->wB[size_t(by) * BW + (x >> 1)];
        float u = bil(U), v = bil(V);
        o[4 * x + 0] = uint8_t(clampf(Y + 1.402f * v + 0.5f, 0.f, 255.f));
        o[4 * x + 1] = uint8_t(clampf(Y - 0.344136f * u - 0.714136f * v + 0.5f, 0.f, 255.f));
        o[4 * x + 2] = uint8_t(clampf(Y + 1.772f * u + 0.5f, 0.f, 255.f));
        o[4 * x + 3] = 255;
      }
    }
  }, 16);
  if (stats) {
    stats->frames = m->frames;
    stats->noise_sigma = m->sigma < 0.f ? 0.f : m->sigma;
    stats->mean_weight = m->frames > 1 ? float(m->weightSum / (m->frames - 1)) : 0.f;
    stats->last_global_dx = m->gdx;
    stats->last_global_dy = m->gdy;
  }
  return LUMEN_OK;
}

void lumen_yuv_merge_destroy(LumenYuvMerge* m) { delete m; }

}  // extern "C"
