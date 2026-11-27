// Astro stacking. Registration uses scale/rotation invariant triangles built
// from each star's nearest neighbours (same idea as Valdes et al. 1995 and
// astroalign), RANSAC on a 2D similarity, then least-squares refinement.
#include "astro.h"

#include <cmath>
#include <cstdio>
#include <limits>
#include <random>

#include "finish.h"
#include "merge.h"

namespace lumen {

static const float kNaN = std::numeric_limits<float>::quiet_NaN();

// ---- star detection ---------------------------------------------------------
static float median_of(std::vector<float>& v) {
  if (v.empty()) return 0.f;
  size_t m = v.size() / 2;
  std::nth_element(v.begin(), v.begin() + long(m), v.end());
  return v[m];
}

static void background_map(const Plane& g, int cell, Plane& bg) {
  const int cx = std::max(1, (g.w + cell - 1) / cell), cy = std::max(1, (g.h + cell - 1) / cell);
  Plane coarse(cx, cy);
  parallel_for(cy, [&](int j0, int j1) {
    std::vector<float> v;
    for (int j = j0; j < j1; ++j)
      for (int i = 0; i < cx; ++i) {
        v.clear();
        for (int y = j * cell; y < std::min(g.h, (j + 1) * cell); y += 2)
          for (int x = i * cell; x < std::min(g.w, (i + 1) * cell); x += 2) v.push_back(g.at(x, y));
        // sigma-clipped median (rejects stars)
        float m = median_of(v);
        std::vector<float> dev;
        dev.reserve(v.size());
        for (float a : v) dev.push_back(std::fabs(a - m));
        float mad = median_of(dev) * 1.4826f + 1e-9f;
        std::vector<float> keep;
        for (float a : v)
          if (a < m + 2.5f * mad) keep.push_back(a);
        coarse.at(i, j) = keep.empty() ? m : median_of(keep);
      }
  }, 1);
  upsample_bilinear(coarse, g.w, g.h, bg);
}

std::vector<Star> detect_stars(const Plane& g, int max_stars) {
  std::vector<Star> stars;
  if (g.w < 16 || g.h < 16) return stars;
  Plane bg;
  background_map(g, 32, bg);
  Plane res(g.w, g.h);
  for (size_t i = 0; i < g.px.size(); ++i) res.px[i] = g.px[i] - bg.px[i];
  Plane sm;
  gaussian_blur(res, sm, 1.0f);
  std::vector<float> samp;
  for (size_t i = 0; i < sm.px.size(); i += 7) samp.push_back(std::fabs(sm.px[i]));
  const float sigma = median_of(samp) * 1.4826f + 1e-9f;
  const float thr = 5.f * sigma;
  struct Cand { int x, y; float v; };
  std::vector<Cand> cands;
  const int B = 4;
  for (int y = B; y < g.h - B; ++y) {
    const float* r = sm.row(y);
    for (int x = B; x < g.w - B; ++x) {
      float v = r[x];
      if (v < thr) continue;
      bool peak = true;
      for (int dy = -2; dy <= 2 && peak; ++dy)
        for (int dx = -2; dx <= 2; ++dx) {
          if (!dx && !dy) continue;
          float o = sm.at(x + dx, y + dy);
          if (o > v || (o == v && (dy < 0 || (dy == 0 && dx < 0)))) {
            peak = false;
            break;
          }
        }
      if (peak) cands.push_back({x, y, v});
    }
  }
  std::sort(cands.begin(), cands.end(), [](const Cand& a, const Cand& b) { return a.v > b.v; });
  for (auto& c : cands) {
    if (int(stars.size()) >= max_stars) break;
    double sx = 0, sy = 0, sw = 0;
    for (int dy = -2; dy <= 2; ++dy)
      for (int dx = -2; dx <= 2; ++dx) {
        float v = std::max(0.f, res.at(c.x + dx, c.y + dy));
        sx += v * (c.x + dx);
        sy += v * (c.y + dy);
        sw += v;
      }
    if (sw <= 0) continue;
    // reject isolated single-pixel spikes (hot pixels / cosmic rays)
    float peak = res.at(c.x, c.y);
    int support = 0;
    if (res.at(c.x - 1, c.y) > 0.2f * peak) ++support;
    if (res.at(c.x + 1, c.y) > 0.2f * peak) ++support;
    if (res.at(c.x, c.y - 1) > 0.2f * peak) ++support;
    if (res.at(c.x, c.y + 1) > 0.2f * peak) ++support;
    if (support < 2) continue;
    Star s;
    s.x = float(sx / sw) * 2.f + 0.5f;  // half-res -> full-res centre
    s.y = float(sy / sw) * 2.f + 0.5f;
    s.flux = float(sw);
    stars.push_back(s);
  }
  return stars;
}

// ---- registration -------------------------------------------------------------
namespace {
struct Tri {
  float i1, i2;  // invariants L1/L0, L2/L0 (L0 longest)
  int v[3];      // vertices: opposite L0, opposite L1, opposite L2
};

void build_triangles(const std::vector<Star>& s, int nuse, int knn, std::vector<Tri>& out) {
  out.clear();
  const int n = std::min(int(s.size()), nuse);
  for (int i = 0; i < n; ++i) {
    std::vector<std::pair<float, int>> d;
    for (int j = 0; j < n; ++j)
      if (j != i) d.push_back({sqr(s[size_t(j)].x - s[size_t(i)].x) + sqr(s[size_t(j)].y - s[size_t(i)].y), j});
    std::sort(d.begin(), d.end());
    const int k = std::min(knn, int(d.size()));
    for (int a = 0; a < k; ++a)
      for (int b = a + 1; b < k; ++b) {
        int idx[3] = {i, d[size_t(a)].second, d[size_t(b)].second};
        auto len = [&](int p, int q) {
          return std::sqrt(sqr(s[size_t(idx[p])].x - s[size_t(idx[q])].x) + sqr(s[size_t(idx[p])].y - s[size_t(idx[q])].y));
        };
        // side opposite vertex t is between the other two
        float L[3] = {len(1, 2), len(0, 2), len(0, 1)};
        int ord[3] = {0, 1, 2};
        std::sort(ord, ord + 3, [&](int p, int q) { return L[p] > L[q]; });
        if (L[ord[0]] < 4.f) continue;
        Tri t;
        t.i1 = L[ord[1]] / L[ord[0]];
        t.i2 = L[ord[2]] / L[ord[0]];
        if (t.i2 < 0.1f) continue;  // degenerate (nearly collinear)
        for (int q = 0; q < 3; ++q) t.v[q] = idx[ord[q]];
        out.push_back(t);
      }
  }
}

bool fit_similarity(const std::vector<std::pair<Star, Star>>& pr, Similarity& T) {
  if (pr.size() < 2) return false;
  double px = 0, py = 0, qx = 0, qy = 0;
  for (auto& p : pr) {
    px += p.first.x; py += p.first.y; qx += p.second.x; qy += p.second.y;
  }
  const double n = double(pr.size());
  px /= n; py /= n; qx /= n; qy /= n;
  double sa = 0, sb = 0, sp = 0;
  for (auto& p : pr) {
    double ax = p.first.x - px, ay = p.first.y - py, bx = p.second.x - qx, by = p.second.y - qy;
    sa += ax * bx + ay * by;
    sb += ax * by - ay * bx;
    sp += ax * ax + ay * ay;
  }
  if (sp <= 1e-9) return false;
  T.a = sa / sp;
  T.b = sb / sp;
  T.tx = qx - (T.a * px - T.b * py);
  T.ty = qy - (T.b * px + T.a * py);
  return true;
}

int nearest(const std::vector<Star>& s, double x, double y, double r2max) {
  int best = -1;
  double bd = r2max;
  for (size_t i = 0; i < s.size(); ++i) {
    double d = sqr(float(s[i].x - x)) + sqr(float(s[i].y - y));
    if (d < bd) {
      bd = d;
      best = int(i);
    }
  }
  return best;
}

int collect_inliers(const std::vector<Star>& ref, const std::vector<Star>& frm, const Similarity& T,
                    double tol, std::vector<std::pair<Star, Star>>* pairs) {
  int n = 0;
  if (pairs) pairs->clear();
  for (auto& r : ref) {
    double x, y;
    T.apply(r.x, r.y, x, y);
    int j = nearest(frm, x, y, tol * tol);
    if (j >= 0) {
      ++n;
      if (pairs) pairs->push_back({r, frm[size_t(j)]});
    }
  }
  return n;
}
}  // namespace

int register_stars(const std::vector<Star>& ref, const std::vector<Star>& frm, Similarity& out,
                   float* rms) {
  if (ref.size() < 4 || frm.size() < 4) return 0;
  std::vector<Tri> tr, tf;
  build_triangles(ref, 40, 6, tr);
  build_triangles(frm, 40, 6, tf);
  if (tr.empty() || tf.empty()) return 0;
  struct M { int r, f; float d; };
  std::vector<M> matches;
  for (size_t i = 0; i < tf.size(); ++i) {
    float bd = 1e9f;
    int bj = -1;
    for (size_t j = 0; j < tr.size(); ++j) {
      float d = sqr(tf[i].i1 - tr[j].i1) + sqr(tf[i].i2 - tr[j].i2);
      if (d < bd) {
        bd = d;
        bj = int(j);
      }
    }
    if (bj >= 0 && bd < 1e-4f) matches.push_back({bj, int(i), bd});
  }
  if (matches.empty()) return 0;
  std::sort(matches.begin(), matches.end(), [](const M& a, const M& b) { return a.d < b.d; });
  if (matches.size() > 400) matches.resize(400);
  Similarity best;
  int bestN = 0;
  std::vector<std::pair<Star, Star>> pr(3);
  for (auto& m : matches) {
    const Tri& a = tr[size_t(m.r)];
    const Tri& b = tf[size_t(m.f)];
    for (int q = 0; q < 3; ++q) pr[size_t(q)] = {ref[size_t(a.v[q])], frm[size_t(b.v[q])]};
    Similarity T;
    if (!fit_similarity(pr, T)) continue;
    const double scale = std::sqrt(T.a * T.a + T.b * T.b);
    if (scale < 0.9 || scale > 1.1) continue;  // same lens: scale ~ 1
    int n = collect_inliers(ref, frm, T, 3.0, nullptr);
    if (n > bestN) {
      bestN = n;
      best = T;
    }
  }
  if (bestN < 4) return 0;
  std::vector<std::pair<Star, Star>> inl;
  for (double tol : {3.0, 2.0, 1.5}) {
    collect_inliers(ref, frm, best, tol, &inl);
    if (inl.size() < 3) break;
    Similarity T;
    if (fit_similarity(inl, T)) best = T;
  }
  collect_inliers(ref, frm, best, 1.5, &inl);
  double e = 0;
  for (auto& p : inl) {
    double x, y;
    best.apply(p.first.x, p.first.y, x, y);
    e += sqr(float(x - p.second.x)) + sqr(float(y - p.second.y));
  }
  if (rms) *rms = inl.empty() ? 0.f : float(std::sqrt(e / double(inl.size())));
  out = best;
  return int(inl.size());
}

// ---- sampling -------------------------------------------------------------------
static inline float cubic(float a, float b, float c, float d, float t) {
  return b + 0.5f * t * (c - a + t * (2.f * a - 5.f * b + 4.f * c - d + t * (3.f * (b - c) + d - a)));
}

static inline float sample_bicubic(const Plane& p, float x, float y) {
  if (!(x >= 0.f && y >= 0.f && x <= float(p.w - 1) && y <= float(p.h - 1))) return kNaN;
  const int ix = int(x), iy = int(y);
  const float fx = x - float(ix), fy = y - float(iy);
  float rows[4];
  for (int j = 0; j < 4; ++j) {
    const float* r = p.row(clampi(iy - 1 + j, 0, p.h - 1));
    rows[j] = cubic(r[clampi(ix - 1, 0, p.w - 1)], r[ix], r[clampi(ix + 1, 0, p.w - 1)],
                    r[clampi(ix + 2, 0, p.w - 1)], fx);
  }
  return cubic(rows[0], rows[1], rows[2], rows[3], fy);
}

// ---- stacker --------------------------------------------------------------------
AstroStacker::AstroStacker(const LumenRawInfo& info, const LumenAstroParams& params)
    : info_(info), params_(params) {
  if (params.temp_dir) temp_dir_ = params.temp_dir;
  params_.temp_dir = nullptr;
  if (params_.kappa <= 0.f) params_.kappa = 2.5f;
  cfa_colors(info.cfa, col_);
  w2_ = info.width / 2;
  h2_ = info.height / 2;
  sum_.reset(w2_, h2_);
  for (auto& c : count_) c.assign(size_t(w2_) * h2_, 0);
  if (params_.stack_mode == LUMEN_STACK_MAXIMUM)
    for (int k = 0; k < 4; ++k) std::fill(sum_.p[k].px.begin(), sum_.p[k].px.end(), -1e9f);
  float s = 0, o = 0;
  for (int k = 0; k < 4; ++k) {
    s += std::max(0.f, info.noise_scale[k]);
    o += std::max(0.f, info.noise_offset[k]);
  }
  S_ = s / 4.f;
  O_ = o / 4.f;
}

AstroStacker::~AstroStacker() {
  for (auto& f : files_) std::remove(f.c_str());
}

void AstroStacker::prepare(const uint16_t* data, BayerPlanes& planes) const {
  if (info_.quad_bayer) {
    std::vector<uint16_t> tmp(size_t(info_.width) * info_.height);
    const int stride = info_.row_stride > 0 ? info_.row_stride / 2 : info_.width;
    for (int y = 0; y < info_.height; ++y)
      std::memcpy(tmp.data() + size_t(y) * info_.width, data + size_t(y) * stride, size_t(info_.width) * 2);
    remosaic_quad_bayer(tmp, info_.width, info_.height);
    LumenRawInfo inf = info_;
    inf.row_stride = info_.width * 2;
    extract_planes(inf, tmp.data(), planes);
  } else {
    extract_planes(info_, data, planes);
  }
}

// Hot pixels are outliers in one CFA plane only; stars brighten all planes.
void AstroStacker::cosmetic(BayerPlanes& pl) const {
  const int W = pl.w, H = pl.h;
  Plane med[4];
  for (int k = 0; k < 4; ++k) {
    med[k].reset(W, H);
    parallel_for(H, [&](int y0, int y1) {
      float v[8];
      for (int y = y0; y < y1; ++y)
        for (int x = 0; x < W; ++x) {
          int n = 0;
          for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx)
              if (dx || dy) v[n++] = pl.p[k].atc(x + dx, y + dy);
          std::nth_element(v, v + 4, v + 8);
          med[k].at(x, y) = v[4];
        }
    }, 8);
  }
  const float floorSig = std::sqrt(std::max(O_, 1e-8f));
  parallel_for(H, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y)
      for (int x = 0; x < W; ++x) {
        float ex[4];
        for (int k = 0; k < 4; ++k) ex[k] = pl.p[k].at(x, y) - med[k].at(x, y);
        for (int k = 0; k < 4; ++k) {
          float m = med[k].at(x, y);
          float sig = std::sqrt(std::max(S_ * std::max(m, 0.f) + O_, 1e-10f));
          float t = std::max(8.f * sig, 6.f * floorSig);
          if (ex[k] < t) continue;
          float others = -1e9f;
          for (int q = 0; q < 4; ++q)
            if (q != k) others = std::max(others, ex[q]);
          if (others < 0.25f * ex[k]) pl.p[k].at(x, y) = m;
        }
      }
  }, 8);
}

int AstroStacker::add_dark(const uint16_t* data) {
  if (finalized_ || frames_ > 0) return LUMEN_ERR_STATE;  // darks first
  BayerPlanes p;
  prepare(data, p);
  if (darks_ == 0) {
    dark_ = p;
  } else {
    for (int k = 0; k < 4; ++k)
      for (size_t i = 0; i < p.p[k].px.size(); ++i) dark_.p[k].px[i] += p.p[k].px[i];
  }
  ++darks_;
  return LUMEN_OK;
}

void AstroStacker::store(const BayerPlanes& wp) {
  const size_t n = size_t(w2_) * h2_;
  const bool maxMode = params_.stack_mode == LUMEN_STACK_MAXIMUM;
  for (int k = 0; k < 4; ++k) {
    float* s = sum_.p[k].px.data();
    uint16_t* c = count_[k].data();
    const float* v = wp.p[k].px.data();
    for (size_t i = 0; i < n; ++i) {
      if (std::isnan(v[i])) continue;
      if (maxMode) s[i] = std::max(s[i], v[i]);
      else s[i] += v[i];
      if (c[i] < 65535) ++c[i];
    }
  }
  if (params_.stack_mode == LUMEN_STACK_SIGMA_CLIP || params_.stack_mode == LUMEN_STACK_MEDIAN) {
    std::vector<uint16_t> q(n * 4);
    for (int k = 0; k < 4; ++k) {
      const float* v = wp.p[k].px.data();
      for (size_t i = 0; i < n; ++i)
        q[size_t(k) * n + i] = std::isnan(v[i]) ? 0 : uint16_t(clampf(std::round((v[i] + 0.1f) * 50000.f), 1.f, 65535.f));
    }
    if (!temp_dir_.empty()) {
      char name[512];
      std::snprintf(name, sizeof(name), "%s/lumen_astro_%p_%d.bin", temp_dir_.c_str(),
                    static_cast<const void*>(this), frames_);
      FILE* f = std::fopen(name, "wb");
      if (f) {
        std::fwrite(q.data(), 2, q.size(), f);
        std::fclose(f);
        files_.push_back(name);
        return;
      }
    }
    mem_frames_.push_back(std::move(q));
  }
}

int AstroStacker::add_light(const uint16_t* data, LumenAstroFrameStats* st) {
  if (finalized_) return LUMEN_ERR_STATE;
  LumenAstroFrameStats local{};
  BayerPlanes p;
  prepare(data, p);
  if (darks_ > 0) {
    const float inv = 1.f / float(darks_);
    for (int k = 0; k < 4; ++k)
      for (size_t i = 0; i < p.p[k].px.size(); ++i) p.p[k].px[i] -= dark_.p[k].px[i] * inv;
  }
  if (S_ <= 0.f && O_ <= 0.f) {
    float s[4], o[4];
    estimate_noise_planes(p, s, o);
    S_ = 0.25f * (s[0] + s[1] + s[2] + s[3]);
    O_ = 0.25f * (o[0] + o[1] + o[2] + o[3]);
  }
  if (params_.remove_hot_pixels) cosmetic(p);
  Similarity T;
  bool identity = true;
  if (params_.align_stars && params_.stack_mode != LUMEN_STACK_MAXIMUM) {
    Plane g;
    planes_to_gray(p, g);
    std::vector<Star> stars = detect_stars(g, 150);
    local.stars_detected = int(stars.size());
    if (!have_ref_) {
      if (stars.size() < 6) {
        if (st) *st = local;
        return LUMEN_ERR_NOT_ENOUGH_STARS;
      }
      ref_stars_ = stars;
      have_ref_ = true;
      local.stars_matched = int(stars.size());
    } else {
      float rms = 0;
      int n = register_stars(ref_stars_, stars, T, &rms);
      local.stars_matched = n;
      local.rms_error_px = rms;
      const int need = std::max(6, int(0.15f * float(std::min(ref_stars_.size(), stars.size()))));
      if (n < need) {
        local.accepted = 0;
        if (st) *st = local;
        return LUMEN_ERR_NOT_ENOUGH_STARS;
      }
      identity = false;
      local.rotation_deg = float(std::atan2(T.b, T.a) * 180.0 / 3.14159265358979);
      local.shift_x = float(T.tx);
      local.shift_y = float(T.ty);
    }
  }
  BayerPlanes w;
  if (identity) {
    w = std::move(p);
  } else {
    w.reset(w2_, h2_);
    for (int k = 0; k < 4; ++k) {
      const float ox = float(k & 1), oy = float(k >> 1);
      const Plane& src = p.p[k];
      Plane& dst = w.p[k];
      parallel_for(h2_, [&](int y0, int y1) {
        for (int j = y0; j < y1; ++j)
          for (int i = 0; i < w2_; ++i) {
            double X, Y;
            T.apply(2.0 * i + ox, 2.0 * j + oy, X, Y);
            dst.at(i, j) = sample_bicubic(src, float((X - ox) * 0.5), float((Y - oy) * 0.5));
          }
      }, 8);
    }
  }
  store(w);
  ++frames_;
  local.accepted = 1;
  if (st) *st = local;
  return LUMEN_OK;
}

void AstroStacker::running_mean(BayerPlanes& out) const {
  out.reset(w2_, h2_);
  const bool maxMode = params_.stack_mode == LUMEN_STACK_MAXIMUM;
  for (int k = 0; k < 4; ++k)
    for (size_t i = 0; i < out.p[k].px.size(); ++i) {
      uint16_t c = count_[k][i];
      out.p[k].px[i] = c ? (maxMode ? sum_.p[k].px[i] : sum_.p[k].px[i] / float(c)) : 0.f;
    }
}

void AstroStacker::remove_gradient(BayerPlanes& pl) const {
  for (int k = 0; k < 4; ++k) {
    Plane& p = pl.p[k];
    const int cell = std::max(8, std::min(p.w, p.h) / 16);
    struct S { double x, y, v; };
    std::vector<S> samples;
    for (int y = 0; y + cell <= p.h; y += cell)
      for (int x = 0; x + cell <= p.w; x += cell) {
        std::vector<float> v;
        for (int yy = y; yy < y + cell; yy += 2)
          for (int xx = x; xx < x + cell; xx += 2) v.push_back(p.at(xx, yy));
        float m = median_of(v);
        samples.push_back({(x + cell * 0.5) / p.w - 0.5, (y + cell * 0.5) / p.h - 0.5, m});
      }
    if (samples.size() < 12) continue;
    double c[6] = {0, 0, 0, 0, 0, 0};
    std::vector<bool> use(samples.size(), true);
    for (int iter = 0; iter < 3; ++iter) {
      double A[6][7] = {};
      for (size_t s = 0; s < samples.size(); ++s) {
        if (!use[s]) continue;
        const double x = samples[s].x, y = samples[s].y;
        const double f[6] = {1, x, y, x * x, x * y, y * y};
        for (int i = 0; i < 6; ++i) {
          for (int j = 0; j < 6; ++j) A[i][j] += f[i] * f[j];
          A[i][6] += f[i] * samples[s].v;
        }
      }
      for (int i = 0; i < 6; ++i) {  // Gauss-Jordan
        int piv = i;
        for (int r = i + 1; r < 6; ++r)
          if (std::fabs(A[r][i]) > std::fabs(A[piv][i])) piv = r;
        for (int q = 0; q < 7; ++q) std::swap(A[i][q], A[piv][q]);
        if (std::fabs(A[i][i]) < 1e-12) continue;
        for (int r = 0; r < 6; ++r) {
          if (r == i) continue;
          double f = A[r][i] / A[i][i];
          for (int q = i; q < 7; ++q) A[r][q] -= f * A[i][q];
        }
      }
      for (int i = 0; i < 6; ++i) c[i] = std::fabs(A[i][i]) > 1e-12 ? A[i][6] / A[i][i] : 0.0;
      std::vector<float> resid;
      for (size_t s = 0; s < samples.size(); ++s) {
        const double x = samples[s].x, y = samples[s].y;
        double fit = c[0] + c[1] * x + c[2] * y + c[3] * x * x + c[4] * x * y + c[5] * y * y;
        resid.push_back(float(std::fabs(samples[s].v - fit)));
      }
      std::vector<float> tmp = resid;
      float mad = median_of(tmp) * 1.4826f + 1e-9f;
      for (size_t s = 0; s < samples.size(); ++s) use[s] = resid[s] < 2.5f * mad;
    }
    const double mean = c[0];  // value at the image centre
    parallel_for(p.h, [&](int y0, int y1) {
      for (int yy = y0; yy < y1; ++yy) {
        const double y = (yy + 0.5) / p.h - 0.5;
        for (int xx = 0; xx < p.w; ++xx) {
          const double x = (xx + 0.5) / p.w - 0.5;
          double fit = c[0] + c[1] * x + c[2] * y + c[3] * x * x + c[4] * x * y + c[5] * y * y;
          p.at(xx, yy) -= float(fit - mean);
        }
      }
    }, 16);
  }
}

int AstroStacker::finalize() {
  if (finalized_) return LUMEN_ERR_STATE;
  if (frames_ == 0) return LUMEN_ERR_STATE;
  const int mode = params_.stack_mode;
  if (mode == LUMEN_STACK_MEAN || mode == LUMEN_STACK_MAXIMUM || frames_ < 3) {
    running_mean(result_);
  } else {
    result_.reset(w2_, h2_);
    const size_t n = size_t(w2_) * h2_;
    const int F = frames_;
    const int band = 32;
    std::vector<FILE*> fh;
    for (auto& f : files_) fh.push_back(std::fopen(f.c_str(), "rb"));
    std::vector<uint16_t> buf(size_t(F) * size_t(band) * size_t(w2_));
    for (int k = 0; k < 4; ++k) {
      for (int y0 = 0; y0 < h2_; y0 += band) {
        const int rows = std::min(band, h2_ - y0);
        const size_t cnt = size_t(rows) * size_t(w2_);
        const size_t off = size_t(k) * n + size_t(y0) * size_t(w2_);
        for (int f = 0; f < F; ++f) {
          uint16_t* dst = buf.data() + size_t(f) * cnt;
          if (size_t(f) < mem_frames_.size()) {
            std::memcpy(dst, mem_frames_[size_t(f)].data() + off, cnt * 2);
          } else {
            FILE* h = fh[size_t(f) - mem_frames_.size()];
            if (!h || std::fseek(h, long(off * 2), SEEK_SET) != 0 || std::fread(dst, 2, cnt, h) != cnt)
              std::memset(dst, 0, cnt * 2);
          }
        }
        parallel_for(int(cnt), [&](int i0, int i1) {
          std::vector<float> v(static_cast<size_t>(F));
          for (int i = i0; i < i1; ++i) {
            int m = 0;
            for (int f = 0; f < F; ++f) {
              uint16_t q = buf[size_t(f) * cnt + size_t(i)];
              if (q) v[size_t(m++)] = float(q) / 50000.f - 0.1f;
            }
            float out = 0.f;
            if (m > 0) {
              if (mode == LUMEN_STACK_MEDIAN || m < 3) {
                std::nth_element(v.begin(), v.begin() + m / 2, v.begin() + m);
                out = v[size_t(m / 2)];
              } else {
                int lo = 0, hi = m;
                std::sort(v.begin(), v.begin() + m);
                for (int it = 0; it < 4; ++it) {
                  double s = 0, s2 = 0;
                  for (int q = lo; q < hi; ++q) { s += v[size_t(q)]; s2 += double(v[size_t(q)]) * v[size_t(q)]; }
                  const double nn = hi - lo;
                  const double mean = s / nn, sd = std::sqrt(std::max(0.0, s2 / nn - mean * mean));
                  int nlo = lo, nhi = hi;
                  while (nlo < nhi && v[size_t(nlo)] < mean - params_.kappa * sd) ++nlo;
                  while (nhi > nlo && v[size_t(nhi - 1)] > mean + params_.kappa * sd) --nhi;
                  if (nhi - nlo < 2 || (nlo == lo && nhi == hi)) break;
                  lo = nlo;
                  hi = nhi;
                }
                double s = 0;
                for (int q = lo; q < hi; ++q) s += v[size_t(q)];
                out = float(s / double(hi - lo));
              }
            }
            result_.p[k].at(i % w2_, y0 + i / w2_) = out;
          }
        }, 1024);
      }
    }
    for (auto h : fh)
      if (h) std::fclose(h);
  }
  // Border pixels not covered by enough registered frames (field rotation /
  // drift) get a colour cast because each CFA plane is warped separately.
  // Replace them with the nearest well-covered pixel toward the centre.
  {
    const uint16_t need = uint16_t(std::max(1, frames_ / 2));
    auto ok = [&](int i, int j) {
      const size_t q = size_t(j) * w2_ + size_t(i);
      return std::min(std::min(count_[0][q], count_[1][q]), std::min(count_[2][q], count_[3][q])) >= need;
    };
    const float ci = 0.5f * float(w2_ - 1), cj = 0.5f * float(h2_ - 1);
    for (int j = 0; j < h2_; ++j)
      for (int i = 0; i < w2_; ++i) {
        if (ok(i, j)) continue;
        float dx = ci - float(i), dy = cj - float(j);
        float len = std::sqrt(dx * dx + dy * dy);
        if (len < 1.f) continue;
        dx /= len;
        dy /= len;
        for (float t = 1.f; t < len; t += 1.f) {
          int si = int(std::lround(float(i) + dx * t)), sj = int(std::lround(float(j) + dy * t));
          if (ok(si, sj)) {
            for (int k = 0; k < 4; ++k) result_.p[k].at(i, j) = result_.p[k].at(si, sj);
            break;
          }
        }
      }
  }
  if (params_.remove_gradient) remove_gradient(result_);
  for (auto& f : files_) std::remove(f.c_str());
  files_.clear();
  mem_frames_.clear();
  finalized_ = true;
  return LUMEN_OK;
}

float AstroStacker::residual_sigma() const {
  const float n = float(std::max(1, frames_));
  return std::sqrt(std::max(0.f, (S_ * 0.05f + O_) / n));
}

// ---- astro rendering: neutral sky + midtone-transfer auto stretch ----------------
static inline float mtf(float m, float x) {
  if (x <= 0.f) return 0.f;
  if (x >= 1.f) return 1.f;
  return ((m - 1.f) * x) / ((2.f * m - 1.f) * x - m);
}

int render_astro(const BayerPlanes& planes, const int col[4], const LumenColorInfo& color,
                 const LumenFinishParams* fp, float sigma, bool trails, bool half, uint8_t* out,
                 int out_stride) {
  const bool p3 = fp ? fp->output_p3 != 0 : false;
  RGB lin;
  build_linear_rgb(planes, col, color, p3, half, fp ? fp->warmth : 0.f, lin);
  const size_t N = size_t(lin.w) * lin.h;
  std::vector<float> ch[3];
  for (size_t i = 0; i < N; i += 7)
    for (int c = 0; c < 3; ++c) ch[c].push_back(lin.px[i * 3 + size_t(c)]);
  float bg[3];
  for (int c = 0; c < 3; ++c) bg[c] = median_of(ch[c]);
  const float bgm = (bg[0] + bg[1] + bg[2]) / 3.f;
  for (size_t i = 0; i < N; ++i)
    for (int c = 0; c < 3; ++c) lin.px[i * 3 + size_t(c)] -= bg[c] - bgm;
  std::vector<float> L;
  L.reserve(N / 7 + 1);
  for (size_t i = 0; i < N; i += 7) L.push_back((lin.px[i * 3] + lin.px[i * 3 + 1] + lin.px[i * 3 + 2]) / 3.f);
  std::vector<float> tmp = L;
  const float med = median_of(tmp);
  std::vector<float> dev;
  dev.reserve(L.size());
  for (float v : L) dev.push_back(std::fabs(v - med));
  const float madn = median_of(dev) * 1.4826f + 1e-7f;
  std::sort(L.begin(), L.end());
  float wp = L.empty() ? 1.f : L[std::min(L.size() - 1, size_t(double(L.size()) * 0.9995))];
  wp = clampf(std::max(wp, med + 20.f * madn), med + 1e-4f, 1.5f);
  const float c0 = std::max(0.f, med - 2.8f * madn);
  float target = trails ? 0.15f : 0.22f;
  if (fp) target = clampf(target * std::pow(2.f, 0.5f * fp->exposure_ev), 0.05f, 0.5f);
  const float xmed = clampf((med - c0) / (wp - c0), 1e-5f, 0.99f);
  const float mt = mtf(target, xmed);
  const float satBoost = 1.35f + (fp ? fp->saturation : 0.f);
  RGB disp(lin.w, lin.h);
  parallel_for(lin.h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y)
      for (int x = 0; x < lin.w; ++x) {
        const float* p = lin.at(x, y);
        float* o = disp.at(x, y);
        float v[3];
        for (int c = 0; c < 3; ++c) v[c] = mtf(mt, clampf((p[c] - c0) / (wp - c0), 0.f, 1.f));
        float l = (v[0] + v[1] + v[2]) / 3.f;
        for (int c = 0; c < 3; ++c) o[c] = clampf(l + (v[c] - l) * satBoost, 0.f, 1.f);
      }
  }, 8);
  lin = RGB();
  const float scale = half ? 0.5f : 1.f;
  const float sd = clampf(sigma * 40.f, 0.f, 0.1f);
  encode_display(disp, 2.5f * scale + 20.f * sd, fp ? fp->sharpen : 0.25f, 0.01f + sd, false, out,
                 out_stride);
  return LUMEN_OK;
}

}  // namespace lumen
