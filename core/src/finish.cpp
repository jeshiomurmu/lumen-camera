// Finishing: demosaic, color, local tone mapping (exposure fusion of synthetic
// exposures, as in HDR+), contrast/saturation, chroma NR, sharpening.
#include "finish.h"

#include <cmath>
#include <mutex>

#include "color.h"

namespace lumen {

// ---- transfer functions ----------------------------------------------------
namespace {
struct OetfLut {
  static constexpr int N = 4096;
  float t[N + 1];
  OetfLut() {
    for (int i = 0; i <= N; ++i) {
      double x = double(i) / N;
      t[i] = float(x <= 0.0031308 ? 12.92 * x : 1.055 * std::pow(x, 1.0 / 2.4) - 0.055);
    }
  }
};
const OetfLut& oetf_lut() {
  static const OetfLut l;
  return l;
}
}  // namespace

float srgb_oetf(float x) {
  if (!(x > 0.f)) return 0.f;
  if (x >= 1.f) return 1.f;
  const auto& l = oetf_lut();
  float f = x * OetfLut::N;
  int i = int(f);
  float a = f - float(i);
  return l.t[i] + a * (l.t[i + 1] - l.t[i]);
}

float srgb_eotf(float v) {
  if (!(v > 0.f)) return 0.f;
  if (v >= 1.f) return 1.f;
  return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

// ---- demosaic ---------------------------------------------------------------
// Malvar-He-Cutler 5x5 gradient-corrected bilinear demosaic (ICASSP 2004).
static void demosaic_mhc(const Plane& m, const int col[4], RGB& out) {
  const int W = m.w, H = m.h;
  out = RGB(W, H);
  // Mirror indexing (-1 -> 1, W -> W-2) keeps the CFA phase at the borders;
  // plain clamping would mix colours and leave a coloured 1-2 px frame.
  auto reflect = [](int v, int n) {
    if (v < 0) v = -v;
    if (v >= n) v = 2 * (n - 1) - v;
    return clampi(v, 0, n - 1);
  };
  parallel_for(H, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      const float* r[5];
      for (int i = 0; i < 5; ++i) r[i] = m.row(reflect(y + i - 2, H));
      for (int x = 0; x < W; ++x) {
        const bool interior = x >= 2 && x < W - 2;
        auto P = [&](int dx, int dy) -> float {
          int xx = interior ? x + dx : reflect(x + dx, W);
          return r[dy + 2][xx];
        };
        const int k = ((y & 1) << 1) | (x & 1);
        const int c = col[k];
        float* o = out.at(x, y);
        const float C = P(0, 0);
        if (c == 1) {
          const int hc = col[((y & 1) << 1) | ((x + 1) & 1)];  // color left/right
          // kernel with same-row neighbours of color hc
          float krow = (5.f * C + 4.f * (P(-1, 0) + P(1, 0)) - (P(-2, 0) + P(2, 0)) -
                        (P(-1, -1) + P(1, -1) + P(-1, 1) + P(1, 1)) + 0.5f * (P(0, -2) + P(0, 2))) /
                       8.f;
          float kcol = (5.f * C + 4.f * (P(0, -1) + P(0, 1)) - (P(0, -2) + P(0, 2)) -
                        (P(-1, -1) + P(1, -1) + P(-1, 1) + P(1, 1)) + 0.5f * (P(-2, 0) + P(2, 0))) /
                       8.f;
          o[1] = C;
          if (hc == 0) {
            o[0] = krow;
            o[2] = kcol;
          } else {
            o[2] = krow;
            o[0] = kcol;
          }
        } else {
          float g = (4.f * C + 2.f * (P(-1, 0) + P(1, 0) + P(0, -1) + P(0, 1)) -
                     (P(-2, 0) + P(2, 0) + P(0, -2) + P(0, 2))) /
                    8.f;
          float d = (6.f * C + 2.f * (P(-1, -1) + P(1, -1) + P(-1, 1) + P(1, 1)) -
                     1.5f * (P(-2, 0) + P(2, 0) + P(0, -2) + P(0, 2))) /
                    8.f;
          o[1] = g;
          if (c == 0) {
            o[0] = C;
            o[2] = d;
          } else {
            o[2] = C;
            o[0] = d;
          }
        }
      }
    }
  }, 8);
}

static Mat3 output_matrix(const LumenColorInfo& color, bool p3) {
  Mat3 m(color.cam_to_srgb);
  if (m.is_zero()) m = Mat3();
  if (p3) m = srgb_to_p3_matrix() * m;
  return m;
}

void build_linear_rgb(const BayerPlanes& planes, const int col[4], const LumenColorInfo& color,
                      bool output_p3, bool half_size, float warmth, RGB& out) {
  float g[3] = {color.wb_gains[0], color.wb_gains[1], color.wb_gains[2]};
  if (!(g[0] > 0 && g[1] > 0 && g[2] > 0)) g[0] = g[1] = g[2] = 1.f;
  const float gn = g[1];
  for (float& v : g) v /= gn;
  // Clip at the level where the least-amplified channel saturates so that
  // blown highlights stay neutral instead of turning pink.
  const float clipv = std::min(g[0], std::min(g[1], g[2]));
  Mat3 M = output_matrix(color, output_p3);
  if (warmth != 0.f) {
    Mat3 warm = Mat3::diag(1.f + 0.08f * warmth, 1.f, 1.f - 0.08f * warmth);
    M = warm * M;
  }
  if (half_size) {
    out = RGB(planes.w, planes.h);
    int rk = 0, bk = 3, gk1 = 1, gk2 = 2;
    for (int k = 0; k < 4; ++k) {
      if (col[k] == 0) rk = k;
      if (col[k] == 2) bk = k;
    }
    {
      int gi = 0;
      int gks[2] = {1, 2};
      for (int k = 0; k < 4; ++k)
        if (col[k] == 1 && gi < 2) gks[gi++] = k;
      gk1 = gks[0];
      gk2 = gks[1];
    }
    parallel_for(planes.h, [&](int y0, int y1) {
      for (int y = y0; y < y1; ++y) {
        for (int x = 0; x < planes.w; ++x) {
          float c[3] = {std::min(planes.p[rk].at(x, y) * g[0], clipv),
                        std::min(0.5f * (planes.p[gk1].at(x, y) + planes.p[gk2].at(x, y)) * g[1], clipv),
                        std::min(planes.p[bk].at(x, y) * g[2], clipv)};
          M.apply(c, out.at(x, y));
        }
      }
    }, 8);
    return;
  }
  Plane mosaic;
  planes_to_mosaic(planes, mosaic);
  parallel_for(mosaic.h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      float* r = mosaic.row(y);
      for (int x = 0; x < mosaic.w; ++x) {
        int k = ((y & 1) << 1) | (x & 1);
        r[x] = std::min(r[x] * g[col[k]], clipv);
      }
    }
  }, 16);
  demosaic_mhc(mosaic, col, out);
  mosaic = Plane();
  parallel_for(out.h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y)
      for (int x = 0; x < out.w; ++x) {
        float* p = out.at(x, y);
        float c[3] = {std::max(0.f, p[0]), std::max(0.f, p[1]), std::max(0.f, p[2])};
        M.apply(c, p);
      }
  }, 8);
}

// ---- pyramids -----------------------------------------------------------------
void upsample_bilinear(const Plane& s, int w, int h, Plane& out) {
  out.reset(w, h);
  const float sx = float(s.w) / float(w), sy = float(s.h) / float(h);
  parallel_for(h, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      float fy = (y + 0.5f) * sy - 0.5f;
      int iy = int(std::floor(fy));
      float ay = fy - float(iy);
      const float* r0 = s.row(clampi(iy, 0, s.h - 1));
      const float* r1 = s.row(clampi(iy + 1, 0, s.h - 1));
      float* d = out.row(y);
      for (int x = 0; x < w; ++x) {
        float fx = (x + 0.5f) * sx - 0.5f;
        int ix = int(std::floor(fx));
        float ax = fx - float(ix);
        int x0 = clampi(ix, 0, s.w - 1), x1 = clampi(ix + 1, 0, s.w - 1);
        float a = r0[x0] + ax * (r0[x1] - r0[x0]);
        float b = r1[x0] + ax * (r1[x1] - r1[x0]);
        d[x] = a + ay * (b - a);
      }
    }
  }, 16);
}

static void gaussian_pyramid(const Plane& base, int levels, std::vector<Plane>& g) {
  g.clear();
  g.push_back(base);
  for (int l = 1; l < levels; ++l) {
    Plane n;
    downsample2(g.back(), n);
    g.push_back(std::move(n));
  }
}

// Mertens-style exposure fusion of display-encoded images.
static void fuse_exposures(const std::vector<Plane>& imgs, const std::vector<Plane>& weights,
                           Plane& out) {
  const int W = imgs[0].w, H = imgs[0].h;
  int levels = 1;
  {
    int m = std::min(W, H);
    while (m > 16 && levels < 9) {
      m = (m + 1) / 2;
      ++levels;
    }
  }
  const size_t n = imgs.size();
  std::vector<std::vector<Plane>> gw(n), gi(n);
  for (size_t i = 0; i < n; ++i) {
    gaussian_pyramid(weights[i], levels, gw[i]);
    gaussian_pyramid(imgs[i], levels, gi[i]);
  }
  // fused laplacian, from coarsest to finest, collapsing on the fly
  Plane acc;
  for (int l = levels - 1; l >= 0; --l) {
    const int lw = gi[0][size_t(l)].w, lh = gi[0][size_t(l)].h;
    Plane fused(lw, lh, 0.f);
    std::vector<Plane> ups(n);
    if (l < levels - 1)
      for (size_t i = 0; i < n; ++i) upsample_bilinear(gi[i][size_t(l + 1)], lw, lh, ups[i]);
    parallel_for(lh, [&](int y0, int y1) {
      for (int y = y0; y < y1; ++y) {
        for (int x = 0; x < lw; ++x) {
          float ws = 1e-12f, v = 0.f;
          for (size_t i = 0; i < n; ++i) {
            float wv = gw[i][size_t(l)].at(x, y);
            float lap = gi[i][size_t(l)].at(x, y) - (l < levels - 1 ? ups[i].at(x, y) : 0.f);
            v += wv * lap;
            ws += wv;
          }
          fused.at(x, y) = v / ws;
        }
      }
    }, 8);
    if (l == levels - 1) {
      acc = std::move(fused);
    } else {
      Plane up;
      upsample_bilinear(acc, lw, lh, up);
      for (size_t p = 0; p < up.px.size(); ++p) up.px[p] += fused.px[p];
      acc = std::move(up);
    }
  }
  out = std::move(acc);
}

static inline float reinhard(float x, float white) {
  return x * (1.f + x / (white * white)) / (1.f + x);
}

static inline float contrast_curve(float y, float c) {
  if (c == 0.f) return y;
  y = clampf(y, 0.f, 1.f);
  const float g = 1.f + 2.f * c;
  float a = std::pow(y, g), b = std::pow(1.f - y, g);
  return a / (a + b + 1e-12f);
}

struct ProfileLook {
  float key, white, highlight_stops, black_lift;
};
static ProfileLook look_for(int profile) {
  switch (profile) {
    case LUMEN_PROFILE_VIVID: return {0.18f, 4.0f, 1.0f, 0.f};
    case LUMEN_PROFILE_FILM: return {0.16f, 2.5f, 1.5f, 0.025f};
    case LUMEN_PROFILE_MONO: return {0.17f, 4.0f, 1.0f, 0.f};
    case LUMEN_PROFILE_NIGHT: return {0.11f, 3.0f, 2.0f, 0.f};
    default: return {0.16f, 4.0f, 1.0f, 0.f};
  }
}

static float auto_gain(const Plane& Y, float key) {
  std::vector<float> logs;
  logs.reserve(Y.px.size() / 4 + 1);
  for (size_t i = 0; i < Y.px.size(); i += 4) logs.push_back(std::log(std::max(Y.px[i], 1e-5f)));
  if (logs.empty()) return 1.f;
  std::sort(logs.begin(), logs.end());
  const size_t lo = logs.size() / 100, hi = logs.size() - logs.size() / 100;
  double s = 0;
  for (size_t i = lo; i < hi; ++i) s += logs[i];
  const float geo = float(std::exp(s / double(std::max<size_t>(1, hi - lo))));
  const float p995 = std::exp(logs[std::min(logs.size() - 1, size_t(double(logs.size()) * 0.995))]);
  float gain = key / std::max(geo, 1e-5f);
  gain = std::min(gain, 8.f / std::max(p995, 1e-5f));  // highlight guard
  return clampf(gain, 0.25f, 64.f);
}

void encode_display(RGB& d, float chroma_sigma, float sharpen, float thr, bool mono, uint8_t* out,
                    int out_stride) {
  const int W = d.w, H = d.h;
  Plane Y(W, H), Cb(W, H), Cr(W, H);
  parallel_for(H, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y)
      for (int x = 0; x < W; ++x) {
        const float* p = d.at(x, y);
        float yy = 0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2];
        Y.at(x, y) = yy;
        Cb.at(x, y) = p[2] - yy;
        Cr.at(x, y) = p[0] - yy;
      }
  }, 16);
  d = RGB();
  if (chroma_sigma > 0.3f && !mono) {
    Plane t;
    gaussian_blur(Cb, t, chroma_sigma);
    Cb = std::move(t);
    gaussian_blur(Cr, t, chroma_sigma);
    Cr = std::move(t);
  }
  Plane blur;
  if (sharpen > 0.f) gaussian_blur(Y, blur, 1.0f);
  const float t2 = thr * thr;
  parallel_for(H, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      uint8_t* o = out + size_t(y) * size_t(out_stride);
      for (int x = 0; x < W; ++x) {
        float yy = Y.at(x, y);
        if (sharpen > 0.f) {
          float det = yy - blur.at(x, y);
          float soft = det * det / (det * det + t2);
          yy += sharpen * det * soft;
        }
        float r, g, b;
        if (mono) {
          r = g = b = yy;
        } else {
          r = yy + Cr.at(x, y);
          b = yy + Cb.at(x, y);
          g = (yy - 0.2126f * r - 0.0722f * b) / 0.7152f;
        }
        o[4 * x + 0] = uint8_t(clampf(r, 0.f, 1.f) * 255.f + 0.5f);
        o[4 * x + 1] = uint8_t(clampf(g, 0.f, 1.f) * 255.f + 0.5f);
        o[4 * x + 2] = uint8_t(clampf(b, 0.f, 1.f) * 255.f + 0.5f);
        o[4 * x + 3] = 255;
      }
    }
  }, 16);
}

int render_photo(const BayerPlanes& planes, const int col[4], const LumenColorInfo& color,
                 const LumenFinishParams& fp, float noise_sigma, uint8_t* out, int out_stride,
                 float* out_display_sigma) {
  const bool p3 = fp.output_p3 != 0;
  RGB lin;
  build_linear_rgb(planes, col, color, p3, fp.half_size != 0, fp.warmth, lin);
  const int W = lin.w, H = lin.h;
  // luma coefficients of the output primaries
  const float ky[3] = {p3 ? 0.2290f : 0.2126f, p3 ? 0.6917f : 0.7152f, p3 ? 0.0793f : 0.0722f};
  Plane Y(W, H);
  parallel_for(H, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y)
      for (int x = 0; x < W; ++x) {
        const float* p = lin.at(x, y);
        Y.at(x, y) = std::max(0.f, ky[0] * p[0] + ky[1] * p[1] + ky[2] * p[2]);
      }
  }, 16);

  const ProfileLook look = look_for(fp.profile);
  // Half-resolution luma for exposure estimation and tone mapping.
  Plane Yh;
  downsample2(Y, Yh);
  float gain = fp.auto_exposure ? auto_gain(Yh, look.key) : 1.f;
  gain *= std::pow(2.f, fp.exposure_ev);

  const float s = clampf(fp.shadows, 0.f, 1.f) * 3.f;   // shadow lift stops
  const float h = look.highlight_stops;                  // highlight recovery
  std::vector<float> stops = {-h, 0.f};
  if (s > 0.05f) stops.push_back(s);
  std::vector<Plane> imgs(stops.size()), wts(stops.size());
  for (size_t i = 0; i < stops.size(); ++i) {
    const float g = gain * std::pow(2.f, stops[i]);
    imgs[i].reset(Yh.w, Yh.h);
    wts[i].reset(Yh.w, Yh.h);
    for (size_t p = 0; p < Yh.px.size(); ++p) {
      float v = srgb_oetf(reinhard(Yh.px[p] * g, look.white));
      imgs[i].px[p] = v;
      float d = v - 0.5f;
      float wv = std::exp(-d * d / (2.f * 0.2f * 0.2f)) + 1e-4f;
      if (i == 1) wv *= 1.25f;  // favour the base exposure (natural look)
      wts[i].px[p] = wv;
    }
  }
  Plane fused;
  fuse_exposures(imgs, wts, fused);
  imgs.clear();
  wts.clear();
  // Tone-mapping gain (log ratio of target to scene luminance) at half
  // resolution, brought to full resolution with a guided filter in the log
  // domain: log r ~= a * log Y + b per window, so every full-res pixel gets
  // the gain that matches its own brightness (no halos at hard edges).
  Plane lg(Yh.w, Yh.h), lr(Yh.w, Yh.h);
  for (size_t p = 0; p < Yh.px.size(); ++p) {
    float t = srgb_eotf(clampf(fused.px[p], 0.f, 1.f));
    lg.px[p] = std::log(Yh.px[p] + 1e-4f);
    lr.px[p] = std::log(std::max(t, 1e-7f)) - lg.px[p];
  }
  fused = Plane();
  Plane ratioFull;
  {
    const int rad = std::max(2, std::min(Yh.w, Yh.h) / 256);
    const float eps = 0.02f;
    Plane gg(Yh.w, Yh.h), gp(Yh.w, Yh.h);
    for (size_t p = 0; p < Yh.px.size(); ++p) {
      gg.px[p] = lg.px[p] * lg.px[p];
      gp.px[p] = lg.px[p] * lr.px[p];
    }
    Plane mg, mp, mgg, mgp;
    box_blur(lg, mg, rad);
    box_blur(lr, mp, rad);
    box_blur(gg, mgg, rad);
    box_blur(gp, mgp, rad);
    Plane a(Yh.w, Yh.h), b(Yh.w, Yh.h);
    for (size_t p = 0; p < Yh.px.size(); ++p) {
      float var = mgg.px[p] - mg.px[p] * mg.px[p];
      float cov = mgp.px[p] - mg.px[p] * mp.px[p];
      a.px[p] = cov / (var + eps);
      b.px[p] = mp.px[p] - a.px[p] * mg.px[p];
    }
    Plane ma, mb;
    box_blur(a, ma, rad);
    box_blur(b, mb, rad);
    Plane A, B;
    upsample_bilinear(ma, W, H, A);
    upsample_bilinear(mb, W, H, B);
    ratioFull.reset(W, H);
    parallel_for(H, [&](int y0, int y1) {
      for (int y = y0; y < y1; ++y)
        for (int x = 0; x < W; ++x) {
          float l = std::log(Y.at(x, y) + 1e-4f);
          ratioFull.at(x, y) = std::exp(A.at(x, y) * l + B.at(x, y));
        }
    }, 16);
  }
  lg = Plane();
  lr = Plane();

  const float sat = fp.profile == LUMEN_PROFILE_MONO ? -1.f : fp.saturation;
  const float maxRatio = gain * std::pow(2.f, std::max(0.f, s)) * 1.5f;
  RGB disp(W, H);
  parallel_for(H, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y)
      for (int x = 0; x < W; ++x) {
        const float* p = lin.at(x, y);
        float yv = Y.at(x, y);
        float r = clampf(ratioFull.at(x, y), 0.f, maxRatio);
        float L = yv * r;  // tone-mapped linear luminance
        // contrast on display luma
        float ld = srgb_oetf(L);
        ld = contrast_curve(ld, fp.contrast);
        float Lt = srgb_eotf(ld);
        float c[3];
        if (yv > 1e-5f) {
          float k = Lt / yv;
          c[0] = p[0] * k;
          c[1] = p[1] * k;
          c[2] = p[2] * k;
        } else {
          c[0] = c[1] = c[2] = Lt;  // avoid amplifying chroma noise at black
        }
        // saturation around luminance
        for (float& v : c) v = Lt + (v - Lt) * (1.f + sat);
        // gamut / highlight compression toward luminance
        float mx = std::max(c[0], std::max(c[1], c[2]));
        if (mx > 1.f) {
          float t = Lt < 1.f ? (1.f - Lt) / std::max(mx - Lt, 1e-6f) : 0.f;
          for (float& v : c) v = Lt + (v - Lt) * t;
        }
        float* o = disp.at(x, y);
        for (int i = 0; i < 3; ++i)
          o[i] = look.black_lift + (1.f - look.black_lift) * srgb_oetf(std::max(0.f, c[i]));
      }
  }, 8);
  lin = RGB();
  ratioFull = Plane();
  Y = Plane();

  // display-domain noise estimate ~ slope of the tone curve at mid grey
  float sigma_d = clampf(noise_sigma * gain * 2.5f, 0.f, 0.2f);
  if (out_display_sigma) *out_display_sigma = sigma_d;
  const float scale = fp.half_size ? 0.5f : 1.f;
  float chroma = clampf(fp.chroma_nr, 0.f, 1.f) * (1.0f + 40.f * sigma_d) * scale;
  chroma = std::min(chroma, 8.f);
  float thr = 0.003f + 1.5f * sigma_d;
  encode_display(disp, chroma, std::max(0.f, fp.sharpen) * (fp.half_size ? 0.6f : 1.f), thr,
                 fp.profile == LUMEN_PROFILE_MONO, out, out_stride);
  return LUMEN_OK;
}

}  // namespace lumen
