// Unit tests for the Lumen imaging core on synthetic, physically-modelled data.
// Build: cmake -S core -B build && cmake --build build && ./build/lumen_tests [outdir]
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <string>
#include <vector>

#include "align.h"
#include "astro.h"
#include "color.h"
#include "common.h"
#include "fft16.h"
#include "lumen_core.h"

using namespace lumen;

static int g_fail = 0, g_pass = 0;
#define CHECK(cond, ...)                                  \
  do {                                                    \
    if (cond) {                                           \
      ++g_pass;                                           \
    } else {                                              \
      ++g_fail;                                           \
      std::printf("  FAIL %s:%d: ", __FILE__, __LINE__);  \
      std::printf(__VA_ARGS__);                           \
      std::printf("\n");                                  \
    }                                                     \
  } while (0)

static std::string g_out = "/tmp";

// ---------------------------------------------------------------------------
// Synthetic camera
struct Camera {
  int W = 512, H = 384;
  float black = 64.f, white = 1023.f;
  float S = 0.0008f, O = 2e-6f;  // noise model (normalized units)
  float gains[3] = {2.0f, 1.0f, 1.6f};
  LumenRawInfo info() const {
    LumenRawInfo i{};
    i.width = W;
    i.height = H;
    i.row_stride = W * 2;
    i.cfa = LUMEN_CFA_RGGB;
    for (int k = 0; k < 4; ++k) {
      i.black_level[k] = black;
      i.noise_scale[k] = S;
      i.noise_offset[k] = O;
    }
    i.white_level = white;
    return i;
  }
};

static float hashf(int x, int y) {
  uint32_t h = uint32_t(x) * 374761393u + uint32_t(y) * 668265263u;
  h = (h ^ (h >> 13)) * 1274126177u;
  return float(h & 0xffffff) / float(0xffffff);
}

// Procedural scene in linear sRGB, continuous coordinates.
static void scene(double x, double y, float rgb[3]) {
  double gx = x / 512.0, gy = y / 384.0;
  float base = 0.08f + 0.25f * float(gx);
  rgb[0] = base * 1.1f;
  rgb[1] = base;
  rgb[2] = base * 0.9f + 0.05f * float(gy);
  // color patches (top-left area)
  if (x > 20 && x < 260 && y > 20 && y < 140) {
    int cx = int((x - 20) / 40), cy = int((y - 20) / 40);
    static const float pal[18][3] = {{0.45f, 0.32f, 0.26f}, {0.75f, 0.58f, 0.50f}, {0.36f, 0.48f, 0.61f},
                                     {0.35f, 0.42f, 0.25f}, {0.51f, 0.50f, 0.69f}, {0.39f, 0.74f, 0.67f},
                                     {0.85f, 0.47f, 0.17f}, {0.29f, 0.36f, 0.65f}, {0.76f, 0.33f, 0.38f},
                                     {0.36f, 0.24f, 0.42f}, {0.62f, 0.74f, 0.25f}, {0.89f, 0.63f, 0.18f},
                                     {0.17f, 0.24f, 0.58f}, {0.27f, 0.58f, 0.29f}, {0.69f, 0.19f, 0.22f},
                                     {0.93f, 0.78f, 0.12f}, {0.73f, 0.33f, 0.59f}, {0.0f, 0.52f, 0.63f}};
    const float* p = pal[(cy * 6 + cx) % 18];
    for (int c = 0; c < 3; ++c) rgb[c] = std::pow(p[c], 2.2f);
    return;
  }
  // zone plate / fine detail (right half)
  if (x > 280 && y > 30 && y < 230) {
    double dx = x - 400, dy = y - 130;
    float z = 0.5f + 0.5f * float(std::cos(0.0009 * (dx * dx + dy * dy)));
    float v = 0.05f + 0.6f * z;
    rgb[0] = rgb[1] = rgb[2] = v;
    return;
  }
  // texture + edges (bottom)
  if (y > 250) {
    int bx = int(x / 6), by = int(y / 6);
    float t = 0.15f + 0.5f * hashf(bx, by);
    bool bar = (int(x / 12) % 2) == 0 && x < 200;
    if (bar) t = 0.8f;
    rgb[0] = t;
    rgb[1] = t * 0.95f;
    rgb[2] = t * 0.85f;
  }
}

// Camera RGB (before WB) from scene linear sRGB. The camera's native response
// is modelled as cam = inv(M) * srgb with a fixed "cam_to_srgb" matrix M.
static const float kCamToSrgb[9] = {1.65f, -0.52f, -0.13f, -0.21f, 1.42f, -0.21f, 0.02f, -0.55f, 1.53f};

static void render_frame(const Camera& cam, double dx, double dy, std::mt19937& rng, bool noisy,
                         std::vector<uint16_t>& out, std::vector<float>* cleanNorm = nullptr,
                         const std::function<void(double, double, float*)>& overlay = nullptr) {
  Mat3 M(kCamToSrgb), Mi;
  M.invert(Mi);
  out.assign(size_t(cam.W) * cam.H, 0);
  if (cleanNorm) cleanNorm->assign(out.size(), 0.f);
  std::normal_distribution<float> nd(0.f, 1.f);
  const int col[4] = {0, 1, 1, 2};
  for (int y = 0; y < cam.H; ++y)
    for (int x = 0; x < cam.W; ++x) {
      // optics: 3x3 supersampled Gaussian PSF (sigma ~0.6 px) like a real lens
      float s[3] = {0, 0, 0};
      static const float wq[3] = {0.25f, 0.5f, 0.25f};
      for (int sy = 0; sy < 3; ++sy)
        for (int sx = 0; sx < 3; ++sx) {
          float t[3];
          const double ox = x + dx + (sx - 1) * 0.6, oy = y + dy + (sy - 1) * 0.6;
          scene(ox, oy, t);
          if (overlay) overlay(ox, oy, t);
          const float w = wq[sx] * wq[sy];
          for (int c = 0; c < 3; ++c) s[c] += w * t[c];
        }
      float c[3];
      Mi.apply(s, c);
      int k = ((y & 1) << 1) | (x & 1);
      int ch = col[k];
      float v = std::max(0.f, c[ch] / cam.gains[ch]) * 0.9f;
      if (cleanNorm) (*cleanNorm)[size_t(y) * cam.W + x] = std::min(v, 1.f);
      if (noisy) v += nd(rng) * std::sqrt(cam.S * std::max(v, 0.f) + cam.O);
      float raw = cam.black + v * (cam.white - cam.black);
      out[size_t(y) * cam.W + x] = uint16_t(clampf(std::round(raw), 0.f, cam.white));
    }
}

static double psnr_planes(const BayerPlanes& a, const std::vector<float>& cleanMosaic, int W) {
  double se = 0;
  size_t n = 0;
  for (int k = 0; k < 4; ++k)
    for (int j = 4; j < a.h - 4; ++j)
      for (int i = 4; i < a.w - 4; ++i) {
        int x = 2 * i + (k & 1), y = 2 * j + (k >> 1);
        double d = a.p[k].at(i, j) - cleanMosaic[size_t(y) * W + x];
        se += d * d;
        ++n;
      }
  return 10.0 * std::log10(1.0 / (se / double(n)));
}

static void mosaic_from_u16(const Camera& cam, const std::vector<uint16_t>& m, BayerPlanes& pl) {
  LumenRawInfo inf = cam.info();
  extract_planes(inf, m.data(), pl);
}

static void write_ppm(const std::string& path, const std::vector<uint8_t>& rgba, int w, int h) {
  FILE* f = std::fopen(path.c_str(), "wb");
  if (!f) return;
  std::fprintf(f, "P6\n%d %d\n255\n", w, h);
  for (int i = 0; i < w * h; ++i) std::fwrite(&rgba[size_t(i) * 4], 1, 3, f);
  std::fclose(f);
}

static LumenColorInfo test_color(const Camera& cam) {
  LumenColorInfo c{};
  for (int i = 0; i < 3; ++i) c.wb_gains[i] = cam.gains[i];
  std::memcpy(c.cam_to_srgb, kCamToSrgb, sizeof(kCamToSrgb));
  c.illuminant1 = 21;
  for (int i = 0; i < 3; ++i) c.as_shot_neutral[i] = 1.f / cam.gains[i];
  return c;
}

// ---------------------------------------------------------------------------
static void test_fft() {
  std::printf("[fft]\n");
  const FFT16& F = fft16();
  float re[256], im[256], r0[256];
  std::mt19937 rng(1);
  std::uniform_real_distribution<float> u(-1, 1);
  for (int i = 0; i < 256; ++i) {
    re[i] = r0[i] = u(rng);
    im[i] = 0;
  }
  double e0 = 0;
  for (float v : r0) e0 += v * v;
  F.fft2d(re, im, false);
  double e1 = 0;
  for (int i = 0; i < 256; ++i) e1 += re[i] * re[i] + im[i] * im[i];
  CHECK(std::fabs(e1 / 256.0 - e0) < 1e-3 * e0, "Parseval %f vs %f", e1 / 256.0, e0);
  F.fft2d(re, im, true);
  double err = 0;
  for (int i = 0; i < 256; ++i) err = std::max(err, double(std::fabs(re[i] / 256.f - r0[i])));
  CHECK(err < 1e-5, "inverse error %g", err);
  // window partition of unity at 50% overlap
  float w1[16];
  for (int i = 0; i < 16; ++i) w1[i] = std::sqrt(F.win[i * 16 + i]);
  for (int i = 0; i < 8; ++i) CHECK(std::fabs(w1[i] + w1[i + 8] - 1.f) < 1e-5f, "window sum");
  CHECK(std::fabs(F.win2sum - 36.f) < 1e-3f, "win2sum %f", F.win2sum);
}

static void test_noise_estimation() {
  std::printf("[noise estimation]\n");
  Camera cam;
  std::mt19937 rng(7);
  std::vector<uint16_t> f;
  render_frame(cam, 0, 0, rng, true, f);
  LumenRawInfo inf = cam.info();
  for (int k = 0; k < 4; ++k) inf.noise_scale[k] = inf.noise_offset[k] = 0;
  lumen_estimate_noise(&inf, f.data());
  float S = 0, O = 0;
  for (int k = 0; k < 4; ++k) {
    S += inf.noise_scale[k] / 4;
    O += inf.noise_offset[k] / 4;
  }
  // compare predicted sigma at a mid level (texture biases slightly upward)
  float sTrue = std::sqrt(cam.S * 0.2f + cam.O), sEst = std::sqrt(S * 0.2f + O);
  std::printf("  S=%.6f (true %.6f) O=%.2e (true %.2e) sigma@0.2 est %.5f true %.5f\n", S, cam.S, O,
              cam.O, sEst, sTrue);
  // texture-heavy synthetic scene biases block statistics upward; the merge
  // only needs the right order of magnitude (platforms usually supply it)
  CHECK(sEst > 0.7f * sTrue && sEst < 1.5f * sTrue, "noise sigma off: %f vs %f", sEst, sTrue);
}

static void test_alignment() {
  std::printf("[alignment]\n");
  Camera cam;
  std::mt19937 rng(3);
  std::vector<uint16_t> a, b;
  render_frame(cam, 0, 0, rng, true, a);
  render_frame(cam, 6, -4, rng, true, b);  // scene moved by (+6,-4) full-res
  BayerPlanes pa, pb;
  mosaic_from_u16(cam, a, pa);
  mosaic_from_u16(cam, b, pb);
  Plane ga, gb;
  planes_to_gray(pa, ga);
  planes_to_gray(pb, gb);
  Pyramid ra, rb;
  build_pyramid(ga, ra);
  build_pyramid(gb, rb);
  Grid g;
  Offsets off;
  align_pyramids(ra, rb, std::getenv("LUMEN_R") ? std::atoi(std::getenv("LUMEN_R")) : 4, g, off);
  // frame b at pixel x shows scene(x+6) => content of ref at x is at x-6 in b
  // only textured tiles carry alignment information
  int good = 0, total = 0;
  for (int ty = 2; ty < g.ny - 2; ++ty)
    for (int tx = 2; tx < g.nx - 2; ++tx) {
      double m = 0, m2 = 0;
      for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
          float v = ga.atc(g.x0(tx) + x, g.y0(ty) + y);
          m += v;
          m2 += double(v) * v;
        }
      m /= 256;
      if (std::sqrt(std::max(0.0, m2 / 256 - m * m)) < 0.03) continue;
      ++total;
      if (off.dx[size_t(off.idx(tx, ty))] == -3 && off.dy[size_t(off.idx(tx, ty))] == 2) ++good;
      else if (std::getenv("LUMEN_DIAG_ALIGN")) std::printf("    bad tile at full-res (%d,%d): (%d,%d)\n", 2*g.x0(tx)+16, 2*g.y0(ty)+16, off.dx[size_t(off.idx(tx, ty))], off.dy[size_t(off.idx(tx, ty))]);
    }
  float frac = float(good) / float(total);
  std::printf("  textured tiles with exact offset: %.1f%% (%d/%d)\n", 100 * frac, good, total);
  CHECK(frac > 0.95f, "alignment accuracy %.2f", frac);
}

static void test_merge(bool writeImages) {
  std::printf("[burst merge, low light, handheld sub-pixel shake]\n");
  Camera cam;
  cam.S = 0.003f;  // ~3 stops darker than the default camera
  cam.O = 1e-5f;
  std::mt19937 rng(11);
  std::uniform_real_distribution<double> jitter(-4.0, 4.0);
  const int N = 8;
  std::vector<std::vector<uint16_t>> frames(N);
  std::vector<std::vector<float>> clean(N);
  std::vector<std::pair<double, double>> shifts(N);
  for (int i = 0; i < N; ++i) {
    shifts[size_t(i)] = {i == 0 ? 0 : jitter(rng), i == 0 ? 0 : jitter(rng)};
    render_frame(cam, shifts[size_t(i)].first, shifts[size_t(i)].second, rng, true, frames[size_t(i)],
                 &clean[size_t(i)]);
  }
  LumenRawInfo inf = cam.info();
  LumenMergeParams mp;
  lumen_merge_params_default(&mp);
  mp.spatial_denoise = 0.f;
  LumenBurst* b = lumen_burst_create(&inf, &mp);
  CHECK(b != nullptr, "create");
  auto t0 = std::chrono::steady_clock::now();
  for (auto& f : frames) CHECK(lumen_burst_add_frame(b, f.data()) == LUMEN_OK, "add");
  LumenBurstStats st{};
  CHECK(lumen_burst_finalize(b, &st) == LUMEN_OK, "finalize");
  double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
  std::printf("  merged %d frames, ref=%d, mean motion %.2f px, %.0f ms\n", st.frames_merged,
              st.reference_index, st.mean_motion_px, ms);
  CHECK(st.frames_merged == N, "frames merged %d", st.frames_merged);
  const int ref = st.reference_index;
  BayerPlanes single;
  mosaic_from_u16(cam, frames[size_t(ref)], single);
  double p1 = psnr_planes(single, clean[size_t(ref)], cam.W);
  std::vector<uint16_t> mos(size_t(cam.W) * cam.H);
  lumen_burst_get_mosaic(b, mos.data(), cam.W * 2);
  BayerPlanes merged;
  {
    LumenRawInfo mi = inf;
    for (float& v : mi.black_level) v = 1024.f;
    mi.white_level = 65535.f;
    extract_planes(mi, mos.data(), merged);
  }
  double p2 = psnr_planes(merged, clean[size_t(ref)], cam.W);
  std::printf("  PSNR single %.2f dB -> merged %.2f dB (+%.2f dB)\n", p1, p2, p2 - p1);
  CHECK(p2 - p1 > 4.5, "merge gain too small: %.2f dB", p2 - p1);

  // with spatial denoise
  mp.spatial_denoise = 0.5f;
  LumenBurst* b2 = lumen_burst_create(&inf, &mp);
  for (auto& f : frames) lumen_burst_add_frame(b2, f.data());
  lumen_burst_finalize(b2, nullptr);
  lumen_burst_get_mosaic(b2, mos.data(), cam.W * 2);
  BayerPlanes merged2;
  {
    LumenRawInfo mi = inf;
    for (float& v : mi.black_level) v = 1024.f;
    mi.white_level = 65535.f;
    extract_planes(mi, mos.data(), merged2);
  }
  double p3 = psnr_planes(merged2, clean[size_t(ref)], cam.W);
  std::printf("  + spatial denoise 0.5: %.2f dB\n", p3);
  CHECK(p3 > p2 - 0.3, "spatial denoise should not hurt much: %.2f vs %.2f", p3, p2);

  if (writeImages) {
    LumenColorInfo color = test_color(cam);
    LumenFinishParams fp;
    lumen_finish_params_for_profile(LUMEN_PROFILE_NATURAL, &fp);
    std::vector<uint8_t> rgba(size_t(cam.W) * cam.H * 4);
    CHECK(lumen_burst_render(b2, &color, &fp, rgba.data(), cam.W * 4) == LUMEN_OK, "render");
    write_ppm(g_out + "/lumen_merged.ppm", rgba, cam.W, cam.H);
    CHECK(lumen_render_raw(&inf, frames[size_t(ref)].data(), &color, &fp, rgba.data(), cam.W * 4) == LUMEN_OK,
          "render single");
    write_ppm(g_out + "/lumen_single.ppm", rgba, cam.W, cam.H);
    for (int prof = 0; prof <= 4; ++prof) {
      lumen_finish_params_for_profile(prof, &fp);
      lumen_burst_render(b2, &color, &fp, rgba.data(), cam.W * 4);
      write_ppm(g_out + "/lumen_profile_" + std::to_string(prof) + ".ppm", rgba, cam.W, cam.H);
    }
    LumenDngMeta meta{};
    meta.make = "LumenTest";
    meta.model = "Synthetic";
    meta.exposure_time_s = 1.f / 120.f;
    meta.iso = 800;
    meta.f_number = 1.8f;
    meta.focal_length_mm = 6.3f;
    meta.orientation = 1;
    std::string dng = g_out + "/lumen_merged.dng";
    CHECK(lumen_burst_write_dng(b2, &color, &meta, dng.c_str()) == LUMEN_OK, "write merged dng");
  }
  lumen_burst_destroy(b);
  lumen_burst_destroy(b2);
}

static void test_ghosting() {
  std::printf("[ghosting]\n");
  Camera cam;
  std::mt19937 rng(5);
  const int N = 6;
  std::vector<std::vector<uint16_t>> frames(N);
  std::vector<std::vector<float>> clean(N);
  for (int i = 0; i < N; ++i) {
    const double ox = 300 + 30.0 * i;  // moving bright square
    auto overlay = [ox](double x, double y, float* s) {
      if (x > ox && x < ox + 28 && y > 280 && y < 330) s[0] = s[1] = s[2] = 0.9f;
    };
    render_frame(cam, 0, 0, rng, true, frames[size_t(i)], &clean[size_t(i)], overlay);
  }
  LumenRawInfo inf = cam.info();
  LumenMergeParams mp;
  lumen_merge_params_default(&mp);
  mp.reference_mode = 1;  // frame 0 is the reference
  mp.spatial_denoise = 0.f;
  if (const char* cs = std::getenv("LUMEN_C")) mp.robustness = float(std::atof(cs));
  LumenBurst* b = lumen_burst_create(&inf, &mp);
  for (auto& f : frames) lumen_burst_add_frame(b, f.data());
  lumen_burst_finalize(b, nullptr);
  std::vector<uint16_t> mos(size_t(cam.W) * cam.H);
  lumen_burst_get_mosaic(b, mos.data(), cam.W * 2);
  BayerPlanes merged;
  LumenRawInfo mi = inf;
  for (float& v : mi.black_level) v = 1024.f;
  mi.white_level = 65535.f;
  extract_planes(mi, mos.data(), merged);
  // naive mean for comparison
  double errMerged = 0, errMean = 0;
  int n = 0;
  for (int y = 282; y < 328; ++y)
    for (int x = 334; x < 470; ++x) {  // where the square passes in frames 1..5
      int k = ((y & 1) << 1) | (x & 1);
      double ref = clean[0][size_t(y) * cam.W + x];
      double m = merged.p[k].at(x / 2, y / 2);
      double mean = 0;
      for (int i = 0; i < N; ++i) mean += clean[size_t(i)][size_t(y) * cam.W + x];
      mean /= N;
      errMerged += std::fabs(m - ref);
      errMean += std::fabs(mean - ref);
      ++n;
    }
  errMerged /= n;
  errMean /= n;
  std::printf("  ghost error: robust merge %.4f vs naive average %.4f\n", errMerged, errMean);
  CHECK(errMerged < 0.25 * errMean, "ghosting not suppressed: %.4f vs %.4f", errMerged, errMean);
  lumen_burst_destroy(b);
}

static void test_color_science() {
  std::printf("[color]\n");
  // A plausible XYZ->camera matrix for D65 and Std A (from a generic CMOS).
  LumenColorInfo c{};
  const float cm65[9] = {0.6722f, -0.0635f, -0.0963f, -0.4287f, 1.2460f, 0.2028f, -0.0908f, 0.2162f, 0.5668f};
  const float cmA[9] = {0.7330f, -0.1590f, -0.0560f, -0.3620f, 1.1530f, 0.2470f, -0.0290f, 0.1770f, 0.7020f};
  std::memcpy(c.color_matrix1, cmA, sizeof(cmA));
  std::memcpy(c.color_matrix2, cm65, sizeof(cm65));
  c.illuminant1 = 17;
  c.illuminant2 = 21;
  for (float T : {2900.f, 4300.f, 6500.f, 8000.f}) {
    float g[3];
    CHECK(lumen_wb_gains_for_cct(&c, T, 0.f, g) == LUMEN_OK, "gains");
    LumenColorInfo t = c;
    for (int i = 0; i < 3; ++i) t.as_shot_neutral[i] = 1.f / g[i];
    float est = lumen_estimate_cct(&t);
    std::printf("  T=%.0fK gains=(%.3f, 1, %.3f) -> estimated %.0fK\n", T, g[0], g[2], est);
    CHECK(std::fabs(est - T) / T < 0.03f, "cct roundtrip %f vs %f", est, T);
  }
  // tint moves neutral off-locus in the expected direction (magenta => G lower)
  float gp[3], gm[3];
  lumen_wb_gains_for_cct(&c, 5500.f, 30.f, gp);
  lumen_wb_gains_for_cct(&c, 5500.f, -30.f, gm);
  CHECK(gp[0] < gm[0] && gp[2] < gm[2], "tint direction");
  // resolve camera->sRGB from DNG matrices at D65 and check neutrality
  LumenColorInfo r = c;
  float g65[3];
  lumen_wb_gains_for_cct(&c, 6504.f, 0.f, g65);
  for (int i = 0; i < 3; ++i) r.as_shot_neutral[i] = 1.f / g65[i];
  lumen_color_resolve(&r);
  float one[3] = {1, 1, 1}, o[3];
  Mat3(r.cam_to_srgb).apply(one, o);
  CHECK(std::fabs(o[0] - 1) < 1e-3 && std::fabs(o[1] - 1) < 1e-3 && std::fabs(o[2] - 1) < 1e-3, "neutral");
  // sRGB red primary through camera model -> back to sRGB
  const float srgbToXyz[9] = {0.4124564f, 0.3575761f, 0.1804375f, 0.2126729f, 0.7151522f, 0.0721750f,
                              0.0193339f, 0.1191920f, 0.9503041f};
  float red[3] = {0.6f, 0.1f, 0.1f}, xyz[3], camv[3], back[3];
  Mat3(srgbToXyz).apply(red, xyz);
  Mat3(cm65).apply(xyz, camv);
  for (int i = 0; i < 3; ++i) camv[i] *= r.wb_gains[i];
  Mat3(r.cam_to_srgb).apply(camv, back);
  float sc = 0.1f / back[1];
  for (float& v : back) v *= sc;
  std::printf("  red (0.6,0.1,0.1) -> (%.3f, %.3f, %.3f)\n", back[0], back[1], back[2]);
  CHECK(std::fabs(back[0] - 0.6f) < 0.03f && std::fabs(back[2] - 0.1f) < 0.02f, "color accuracy");
}

static void test_finish_neutral() {
  std::printf("[finish neutral]\n");
  Camera cam;
  LumenRawInfo inf = cam.info();
  std::vector<uint16_t> f(size_t(cam.W) * cam.H);
  for (int y = 0; y < cam.H; ++y)
    for (int x = 0; x < cam.W; ++x) {
      int ch = (y & 1) ? ((x & 1) ? 2 : 1) : ((x & 1) ? 1 : 0);
      float v = 0.2f / cam.gains[ch];
      f[size_t(y) * cam.W + x] = uint16_t(cam.black + v * (cam.white - cam.black));
    }
  LumenColorInfo color = test_color(cam);
  LumenFinishParams fp;
  lumen_finish_params_for_profile(LUMEN_PROFILE_NATURAL, &fp);
  std::vector<uint8_t> rgba(size_t(cam.W) * cam.H * 4);
  lumen_render_raw(&inf, f.data(), &color, &fp, rgba.data(), cam.W * 4);
  size_t mid = (size_t(cam.H / 2) * cam.W + cam.W / 2) * 4;
  int r = rgba[mid], g = rgba[mid + 1], b = rgba[mid + 2];
  std::printf("  grey -> (%d, %d, %d)\n", r, g, b);
  CHECK(std::abs(r - g) <= 2 && std::abs(b - g) <= 2 && g > 60 && g < 200, "neutral render");
  // half size path
  fp.half_size = 1;
  std::vector<uint8_t> half(size_t(cam.W / 2) * (cam.H / 2) * 4);
  CHECK(lumen_render_raw(&inf, f.data(), &color, &fp, half.data(), (cam.W / 2) * 4) == LUMEN_OK, "half");
}

static void test_dng_roundtrip() {
  std::printf("[dng]\n");
  Camera cam;
  std::mt19937 rng(9);
  std::vector<uint16_t> f;
  render_frame(cam, 0, 0, rng, true, f);
  LumenRawInfo inf = cam.info();
  LumenColorInfo c = test_color(cam);
  const float cm65[9] = {0.6722f, -0.0635f, -0.0963f, -0.4287f, 1.2460f, 0.2028f, -0.0908f, 0.2162f, 0.5668f};
  std::memcpy(c.color_matrix1, cm65, sizeof(cm65));
  c.illuminant1 = 21;
  c.baseline_exposure = 0.25f;
  LumenDngMeta meta{};
  meta.make = "LumenTest";
  meta.model = "Synthetic Cam";
  meta.exposure_time_s = 1.f / 60.f;
  meta.iso = 400;
  meta.f_number = 1.8f;
  meta.focal_length_mm = 5.6f;
  meta.orientation = 6;
  std::string path = g_out + "/lumen_single.dng";
  CHECK(lumen_write_dng(&inf, f.data(), &c, &meta, path.c_str()) == LUMEN_OK, "write");
  FILE* fh = std::fopen(path.c_str(), "rb");
  std::vector<uint8_t> bytes;
  if (fh) {
    std::fseek(fh, 0, SEEK_END);
    bytes.resize(size_t(std::ftell(fh)));
    std::fseek(fh, 0, SEEK_SET);
    size_t rd = std::fread(bytes.data(), 1, bytes.size(), fh);
    (void)rd;
    std::fclose(fh);
  }
  LumenDngInfo di;
  CHECK(lumen_dng_read_info(bytes.data(), bytes.size(), &di) == LUMEN_OK, "read info");
  CHECK(di.raw.width == cam.W && di.raw.height == cam.H, "dims");
  CHECK(di.raw.cfa == LUMEN_CFA_RGGB, "cfa");
  CHECK(std::fabs(di.raw.black_level[2] - cam.black) < 1e-3f, "black");
  CHECK(std::fabs(di.raw.white_level - cam.white) < 1e-3f, "white");
  CHECK(std::fabs(di.color.color_matrix1[4] - cm65[4]) < 1e-3f, "matrix");
  CHECK(std::fabs(di.color.as_shot_neutral[0] - 1.f / cam.gains[0]) < 1e-3f, "neutral");
  CHECK(di.iso == 400 && di.orientation == 6, "exif iso/orientation");
  CHECK(std::fabs(di.exposure_time_s - 1.f / 60.f) < 1e-5f, "exposure %f", di.exposure_time_s);
  CHECK(std::string(di.make) == "LumenTest", "make");
  CHECK(di.has_noise_profile && std::fabs(di.raw.noise_scale[0] - cam.S) < 1e-6f, "noise profile");
  std::vector<uint16_t> px(f.size());
  CHECK(lumen_dng_read_pixels(bytes.data(), bytes.size(), px.data()) == LUMEN_OK, "pixels");
  CHECK(px == f, "pixel roundtrip");
}

// ---------------------------------------------------------------------------
struct SkyStar {
  double x, y, flux;
};

static void render_sky(int W, int H, const std::vector<SkyStar>& stars, double angleDeg, double tx, double ty,
                       std::mt19937& rng, const std::vector<std::pair<int, int>>& hot, bool satellite,
                       std::vector<uint16_t>& out, double cx, double cy) {
  const float black = 256.f, white = 4095.f;
  const float S = 0.0004f, O = 4e-6f;
  std::vector<float> img(size_t(W) * H);
  for (int y = 0; y < H; ++y)
    for (int x = 0; x < W; ++x) img[size_t(y) * W + x] = 0.04f + 0.02f * float(x) / W + 0.01f * float(y) / H;
  const double a = angleDeg * 3.14159265358979 / 180.0;
  const double ca = std::cos(a), sa = std::sin(a);
  const double sig = 1.3;
  for (auto& s : stars) {
    // rotate around (cx, cy) then translate
    double X = cx + ca * (s.x - cx) - sa * (s.y - cy) + tx;
    double Y = cy + sa * (s.x - cx) + ca * (s.y - cy) + ty;
    for (int yy = int(Y) - 5; yy <= int(Y) + 5; ++yy)
      for (int xx = int(X) - 5; xx <= int(X) + 5; ++xx) {
        if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
        double d2 = (xx + 0.5 - X) * (xx + 0.5 - X) + (yy + 0.5 - Y) * (yy + 0.5 - Y);
        img[size_t(yy) * W + xx] += float(s.flux * std::exp(-d2 / (2 * sig * sig)) / (2 * 3.14159 * sig * sig));
      }
  }
  if (satellite)
    for (int x = 0; x < W; ++x) {
      int y = int(60 + 0.4 * x);
      for (int d = -1; d <= 1; ++d)
        if (y + d >= 0 && y + d < H) img[size_t(y + d) * W + x] += 0.3f;
    }
  std::normal_distribution<float> nd(0.f, 1.f);
  out.assign(img.size(), 0);
  for (size_t i = 0; i < img.size(); ++i) {
    float v = std::min(img[i], 1.f);
    v += nd(rng) * std::sqrt(S * std::max(v, 0.f) + O);
    out[i] = uint16_t(clampf(std::round(black + v * (white - black)), 0.f, white));
  }
  for (auto& h : hot) out[size_t(h.second) * W + h.first] = uint16_t(black + 0.6f * (white - black));
}

static void test_astro(bool writeImages) {
  std::printf("[astro]\n");
  const int W = 768, H = 512;
  std::mt19937 rng(21);
  std::uniform_real_distribution<double> ux(10, W - 10), uy(10, H - 10), uf(0, 1);
  std::vector<SkyStar> stars;
  for (int i = 0; i < 350; ++i) stars.push_back({ux(rng), uy(rng), 0.3 + 6.0 * std::pow(uf(rng), 4.0)});
  std::vector<std::pair<int, int>> hot;
  for (int i = 0; i < 60; ++i) hot.push_back({int(ux(rng)), int(uy(rng))});
  LumenRawInfo inf{};
  inf.width = W;
  inf.height = H;
  inf.row_stride = W * 2;
  inf.cfa = LUMEN_CFA_RGGB;
  for (int k = 0; k < 4; ++k) {
    inf.black_level[k] = 256;
    inf.noise_scale[k] = 0.0004f;
    inf.noise_offset[k] = 4e-6f;
  }
  inf.white_level = 4095;
  LumenAstroParams ap;
  lumen_astro_params_default(&ap);
  ap.temp_dir = g_out.c_str();
  LumenAstro* a = lumen_astro_create(&inf, &ap);
  CHECK(a != nullptr, "astro create");
  const int N = 12;
  const double cx = W * 0.5, cy = -900;  // pole above the frame
  double maxErr = 0;
  std::vector<uint16_t> f0;
  for (int i = 0; i < N; ++i) {
    std::vector<uint16_t> f;
    double ang = 0.06 * i, tx = 1.7 * i, ty = -0.9 * i;
    render_sky(W, H, stars, ang, tx, ty, rng, hot, i == 5, f, cx, cy);
    if (i == 0) f0 = f;
    LumenAstroFrameStats st{};
    int rc = lumen_astro_add_light(a, f.data(), &st);
    CHECK(rc == LUMEN_OK, "add light %d rc=%d", i, rc);
    if (i > 0) {
      // ground truth: ref (frame0) coords -> frame coords
      double ra = ang * 3.14159265358979 / 180.0;
      double ex = cx + std::cos(ra) * (300 - cx) - std::sin(ra) * (200 - cy) + tx;
      double ey = cy + std::sin(ra) * (300 - cx) + std::cos(ra) * (200 - cy) + ty;
      // estimated similarity from stats: rotation + shift
      double ea = st.rotation_deg * 3.14159265358979 / 180.0;
      double gx = std::cos(ea) * 300 - std::sin(ea) * 200 + st.shift_x;
      double gy = std::sin(ea) * 300 + std::cos(ea) * 200 + st.shift_y;
      double err = std::sqrt((gx - ex) * (gx - ex) + (gy - ey) * (gy - ey));
      maxErr = std::max(maxErr, err);
      if (i == 1 || i == N - 1)
        std::printf("  frame %d: stars %d matched %d rot %.3f deg (true %.3f) rms %.3f px err %.3f px\n", i,
                    st.stars_detected, st.stars_matched, st.rotation_deg, ang, st.rms_error_px, err);
    }
  }
  CHECK(maxErr < 0.35, "registration error %.3f px", maxErr);
  CHECK(lumen_astro_finalize(a) == LUMEN_OK, "finalize");
  // noise reduction: compare background std in a star-free-ish patch
  LumenColorInfo color{};
  color.wb_gains[0] = 1.8f;
  color.wb_gains[1] = 1.f;
  color.wb_gains[2] = 1.5f;
  const float I[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
  std::memcpy(color.cam_to_srgb, I, sizeof(I));
  std::vector<uint16_t> mos;
  {
    // single-frame vs stacked residual via DNG write/read of the stack
    std::string path = g_out + "/lumen_astro.dng";
    LumenDngMeta meta{};
    meta.make = "LumenTest";
    meta.model = "Sky";
    CHECK(lumen_astro_write_dng(a, &color, &meta, path.c_str()) == LUMEN_OK, "astro dng");
    FILE* fh = std::fopen(path.c_str(), "rb");
    std::vector<uint8_t> bytes;
    if (fh) {
      std::fseek(fh, 0, SEEK_END);
      bytes.resize(size_t(std::ftell(fh)));
      std::fseek(fh, 0, SEEK_SET);
      size_t rd = std::fread(bytes.data(), 1, bytes.size(), fh);
      (void)rd;
      std::fclose(fh);
    }
    mos.resize(size_t(W) * H);
    lumen_dng_read_pixels(bytes.data(), bytes.size(), mos.data());
  }
  auto patch_std = [&](const std::vector<uint16_t>& m, float black, float white, int x0, int y0) {
    std::vector<double> v;
    for (int y = y0; y < y0 + 40; y += 2)
      for (int x = x0; x < x0 + 40; x += 2) v.push_back((m[size_t(y) * W + x] - black) / (white - black));
    std::sort(v.begin(), v.end());
    // robust std via IQR
    return (v[v.size() * 3 / 4] - v[v.size() / 4]) / 1.349;
  };
  double s1 = patch_std(f0, 256, 4095, 600, 400);
  double s2 = patch_std(mos, 1024, 65535, 600, 400);
  std::printf("  background noise: single %.5f -> stacked %.5f (x%.2f better)\n", s1, s2, s1 / s2);
  CHECK(s1 / s2 > 2.3, "stack noise reduction %.2f", s1 / s2);
  // hot pixels removed (check a few positions that are not near stars)
  int hotOk = 0, hotN = 0;
  for (auto& h : hot) {
    if (h.first < 4 || h.second < 4 || h.first >= W - 4 || h.second >= H - 4) continue;
    double v = (mos[size_t(h.second) * W + h.first] - 1024.0) / (65535.0 - 1024.0);
    ++hotN;
    if (v < 0.2) ++hotOk;
  }
  std::printf("  hot pixels suppressed: %d/%d\n", hotOk, hotN);
  CHECK(hotOk > hotN * 0.9, "hot pixels");
  // satellite trail rejected by sigma clipping (frame 5 only)
  double trail = 0;
  int tn = 0;
  for (int x = 100; x < 700; x += 7) {
    int y = int(60 + 0.4 * x);
    trail += (mos[size_t(y) * W + x] - 1024.0) / (65535.0 - 1024.0);
    ++tn;
  }
  trail /= tn;
  std::printf("  mean value along satellite trail: %.4f (background ~0.05)\n", trail);
  CHECK(trail < 0.08, "satellite not rejected: %.4f", trail);
  if (writeImages) {
    std::vector<uint8_t> rgba(size_t(W) * H * 4);
    CHECK(lumen_astro_render(a, &color, nullptr, rgba.data(), W * 4) == LUMEN_OK, "astro render");
    write_ppm(g_out + "/lumen_astro.ppm", rgba, W, H);
  }
  lumen_astro_destroy(a);
}

static void test_log_and_misc() {
  std::printf("[log curve, quad bayer, raw10]\n");
  float maxErr = 0, prev = -1;
  bool mono = true;
  for (int i = 0; i <= 1000; ++i) {
    float x = float(i) / 1000.f;
    float e = lumen_log_encode(x);
    if (e < prev) mono = false;
    prev = e;
    maxErr = std::max(maxErr, std::fabs(lumen_log_decode(e) - x));
  }
  CHECK(mono, "log curve monotonic");
  CHECK(maxErr < 1e-4f, "log roundtrip %g", maxErr);
  std::printf("  log(0.18) = %.3f\n", lumen_log_encode(0.18f));
  std::string lut = g_out + "/LumenLog_to_Rec709.cube";
  CHECK(lumen_write_log_lut(lut.c_str(), 33) == LUMEN_OK, "lut");
  // quad bayer naive remosaic: build a quad RGGB pattern of color codes
  const int W = 8, H = 8;
  std::vector<uint16_t> q(size_t(W) * H);
  for (int y = 0; y < H; ++y)
    for (int x = 0; x < W; ++x) {
      int cy = (y / 2) & 1, cx = (x / 2) & 1;  // quad cells
      int color = cy == 0 ? (cx == 0 ? 0 : 1) : (cx == 0 ? 1 : 2);
      q[size_t(y) * W + x] = uint16_t(color);
    }
  remosaic_quad_bayer(q, W, H);
  bool ok = true;
  for (int y = 0; y < H; ++y)
    for (int x = 0; x < W; ++x) {
      int want = (y & 1) ? ((x & 1) ? 2 : 1) : ((x & 1) ? 1 : 0);
      if (q[size_t(y) * W + x] != want) ok = false;
    }
  CHECK(ok, "quad bayer remosaic");
  // raw10 unpack
  uint8_t packed[5] = {0xAB, 0xCD, 0x12, 0x34, 0b11100100};
  uint16_t up[4];
  unpack_raw10(packed, 4, 1, 5, up);
  CHECK(up[0] == ((0xAB << 2) | 0) && up[1] == ((0xCD << 2) | 1) && up[2] == ((0x12 << 2) | 2) &&
            up[3] == ((0x34 << 2) | 3),
        "raw10");
}

// Diagnostics: merge gain vs robustness and alignment granularity.
static void diag_merge() {
  std::printf("[diag merge]\n");
  Camera cam;
  for (int mode = 0; mode < 3; ++mode) {
    std::mt19937 rng(11);
    std::uniform_real_distribution<double> jitter(-4.0, 4.0);
    std::uniform_int_distribution<int> ij(-2, 2);
    const int N = 8;
    std::vector<std::vector<uint16_t>> frames(N);
    std::vector<std::vector<float>> clean(N);
    for (int i = 0; i < N; ++i) {
      double dx = 0, dy = 0;
      if (i > 0 && mode == 1) { dx = 2 * ij(rng); dy = 2 * ij(rng); }   // even integer shifts
      if (i > 0 && mode == 2) { dx = jitter(rng); dy = jitter(rng); }   // subpixel shifts
      render_frame(cam, dx, dy, rng, true, frames[size_t(i)], &clean[size_t(i)]);
    }
    for (float c : {8.f, 12.f, 16.f, 32.f}) {
      LumenRawInfo inf = cam.info();
      LumenMergeParams mp;
      lumen_merge_params_default(&mp);
      mp.spatial_denoise = 0.f;
      mp.reference_mode = 1;
      mp.robustness = c;
      LumenBurst* b = lumen_burst_create(&inf, &mp);
      for (auto& f : frames) lumen_burst_add_frame(b, f.data());
      lumen_burst_finalize(b, nullptr);
      std::vector<uint16_t> mos(size_t(cam.W) * cam.H);
      lumen_burst_get_mosaic(b, mos.data(), cam.W * 2);
      BayerPlanes merged, single;
      LumenRawInfo mi = inf;
      for (float& v : mi.black_level) v = 1024.f;
      mi.white_level = 65535.f;
      extract_planes(mi, mos.data(), merged);
      mosaic_from_u16(cam, frames[0], single);
      double p1 = psnr_planes(single, clean[0], cam.W), p2 = psnr_planes(merged, clean[0], cam.W);
      std::printf("  shifts=%s c=%.0f: %.2f -> %.2f dB (+%.2f)\n", mode == 0 ? "none" : mode == 1 ? "even" : "subpx",
                  c, p1, p2, p2 - p1);
      lumen_burst_destroy(b);
    }
  }
}

static void test_performance() {
  std::printf("[performance 12MP]\n");
  Camera cam;
  cam.W = 4000;
  cam.H = 3000;
  LumenRawInfo inf = cam.info();
  std::mt19937 rng(1);
  std::normal_distribution<float> nd(0, 1);
  std::vector<uint16_t> f(size_t(cam.W) * cam.H);
  for (size_t i = 0; i < f.size(); ++i) f[i] = uint16_t(clampf(300.f + 40.f * nd(rng) + float((i * 7) % 400), 0, 1023));
  LumenBurst* b = lumen_burst_create(&inf, nullptr);
  auto t0 = std::chrono::steady_clock::now();
  const int N = 4;
  for (int i = 0; i < N; ++i) lumen_burst_add_frame(b, f.data());
  auto t1 = std::chrono::steady_clock::now();
  lumen_burst_finalize(b, nullptr);
  auto t2 = std::chrono::steady_clock::now();
  LumenColorInfo color = test_color(cam);
  LumenFinishParams fp;
  lumen_finish_params_for_profile(LUMEN_PROFILE_NATURAL, &fp);
  std::vector<uint8_t> rgba(size_t(cam.W) * cam.H * 4);
  lumen_burst_render(b, &color, &fp, rgba.data(), cam.W * 4);
  auto t3 = std::chrono::steady_clock::now();
  auto ms = [](auto a, auto b2) { return std::chrono::duration<double, std::milli>(b2 - a).count(); };
  std::printf("  %u threads: add+align+merge %d frames %.0f ms (%.0f ms/frame), finalize %.0f ms, render %.0f ms\n",
              std::thread::hardware_concurrency(), N, ms(t0, t1), ms(t0, t1) / N, ms(t1, t2), ms(t2, t3));
  lumen_burst_destroy(b);
  ++g_pass;
}

int main(int argc, char** argv) {
  if (argc > 1) g_out = argv[1];
  bool perf = std::getenv("LUMEN_PERF") != nullptr;
  std::printf("Lumen core %s tests (output: %s)\n", lumen_core_version(), g_out.c_str());
  test_fft();
  test_noise_estimation();
  test_alignment();
  test_merge(true);
  test_ghosting();
  test_color_science();
  test_finish_neutral();
  test_dng_roundtrip();
  test_astro(true);
  test_log_and_misc();
  if (perf) test_performance();
  if (std::getenv("LUMEN_DIAG")) diag_merge();
  std::printf("\n%d checks passed, %d failed\n", g_pass, g_fail);
  return g_fail == 0 ? 0 : 1;
}
