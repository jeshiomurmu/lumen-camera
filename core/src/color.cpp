// Color science: DNG matrix interpolation, white balance from Kelvin/tint,
// camera -> sRGB / Display P3 transforms.
#include "color.h"

#include <cmath>

namespace lumen {

static const float kXYZD50_to_sRGB[9] = {3.1338561f, -1.6168667f, -0.4906146f,
                                         -0.9787684f, 1.9161415f, 0.0334540f,
                                         0.0719453f, -0.2289914f, 1.4052427f};
static const float kXYZD65_to_sRGB[9] = {3.2404542f, -1.5371385f, -0.4985314f,
                                         -0.9692660f, 1.8760108f, 0.0415560f,
                                         0.0556434f, -0.2040259f, 1.0572252f};
static const float kSRGB_to_XYZD65[9] = {0.4124564f, 0.3575761f, 0.1804375f,
                                         0.2126729f, 0.7151522f, 0.0721750f,
                                         0.0193339f, 0.1191920f, 0.9503041f};
static const float kXYZD65_to_P3[9] = {2.4934969f, -0.9313836f, -0.4027108f,
                                       -0.8294890f, 1.7626641f, 0.0236247f,
                                       0.0358458f, -0.0761724f, 0.9568845f};
static const float kBradford[9] = {0.8951f, 0.2664f, -0.1614f, -0.7502f, 1.7135f,
                                   0.0367f, 0.0389f, -0.0685f, 1.0296f};

bool has_matrix(const float* m) {
  for (int i = 0; i < 9; ++i)
    if (m[i] != 0.f) return true;
  return false;
}

float illuminant_cct(int code) {
  switch (code) {
    case 1: return 5500.f;   // daylight
    case 2: return 4150.f;   // fluorescent
    case 3: return 2856.f;   // tungsten
    case 4: return 5500.f;   // flash
    case 9: return 5500.f;   // fine weather
    case 10: return 6500.f;  // cloudy
    case 11: return 7500.f;  // shade
    case 12: return 6430.f;  // daylight fluorescent
    case 13: return 5000.f;  // day white fluorescent
    case 14: return 4150.f;  // cool white fluorescent
    case 15: return 3450.f;  // white fluorescent
    case 16: return 2940.f;  // warm white fluorescent
    case 17: return 2856.f;  // standard A
    case 18: return 4874.f;  // standard B
    case 19: return 6774.f;  // standard C
    case 20: return 5503.f;  // D55
    case 21: return 6504.f;  // D65
    case 22: return 7504.f;  // D75
    case 23: return 5003.f;  // D50
    case 24: return 3200.f;  // ISO studio tungsten
    default: return 0.f;
  }
}

// Planckian locus (Kim et al. 2002 cubic spline), valid 1667..25000 K.
static void planck_xy(double T, double& x, double& y) {
  T = std::min(25000.0, std::max(1667.0, T));
  const double t1 = 1e3 / T, t2 = t1 * t1, t3 = t2 * t1;
  if (T <= 4000.0)
    x = -0.2661239 * t3 - 0.2343589 * t2 + 0.8776956 * t1 + 0.179910;
  else
    x = -3.0258469 * t3 + 2.1070379 * t2 + 0.2226347 * t1 + 0.240390;
  const double x2 = x * x, x3 = x2 * x;
  if (T <= 2222.0)
    y = -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683;
  else if (T <= 4000.0)
    y = -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867;
  else
    y = 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483;
}

static void xy_to_uv(double x, double y, double& u, double& v) {
  double d = -2.0 * x + 12.0 * y + 3.0;
  u = 4.0 * x / d;
  v = 6.0 * y / d;
}
static void uv_to_xy(double u, double v, double& x, double& y) {
  double d = 2.0 * u - 8.0 * v + 4.0;
  x = 3.0 * u / d;
  y = 2.0 * v / d;
}

// Tint follows the Adobe convention: +tint = magenta, offset du,dv = tint/3000
// perpendicular to the locus in CIE 1960 uv.
void cct_tint_to_xy(float kelvin, float tint, double& x, double& y) {
  double T = kelvin;
  planck_xy(T, x, y);
  if (tint == 0.f) return;
  double u, v, u1, v1, u2, v2, xa, ya, xb, yb;
  xy_to_uv(x, y, u, v);
  planck_xy(T * 0.99, xa, ya);
  planck_xy(T * 1.01, xb, yb);
  xy_to_uv(xa, ya, u1, v1);
  xy_to_uv(xb, yb, u2, v2);
  double tu = u2 - u1, tv = v2 - v1;
  double len = std::sqrt(tu * tu + tv * tv);
  if (len < 1e-12) return;
  tu /= len;
  tv /= len;
  double nu = -tv, nv = tu;  // normal
  if (nv < 0) {              // orient toward green (+v)
    nu = -nu;
    nv = -nv;
  }
  const double d = -double(tint) / 3000.0;
  uv_to_xy(u + nu * d, v + nv * d, x, y);
}

static Mat3 lerp(const float* a, const float* b, float t) {
  Mat3 r;
  for (int i = 0; i < 9; ++i) r.m[i] = a[i] * t + b[i] * (1.f - t);
  return r;
}

// Weight of matrix 1 for a CCT using inverse-temperature interpolation.
static float matrix_weight(const LumenColorInfo& c, float cct) {
  if (!has_matrix(c.color_matrix2)) return 1.f;
  float t1 = illuminant_cct(c.illuminant1), t2 = illuminant_cct(c.illuminant2);
  if (t1 <= 0 || t2 <= 0 || std::fabs(t1 - t2) < 1.f) return 1.f;
  float lo = std::min(t1, t2), hi = std::max(t1, t2);
  float T = clampf(cct, lo, hi);
  float w = (1.f / T - 1.f / t2) / (1.f / t1 - 1.f / t2);
  return clampf(w, 0.f, 1.f);
}

static Mat3 color_matrix_at(const LumenColorInfo& c, float cct) {
  if (!has_matrix(c.color_matrix2)) return Mat3(c.color_matrix1);
  return lerp(c.color_matrix1, c.color_matrix2, matrix_weight(c, cct));
}

bool camera_neutral_for_cct(const LumenColorInfo& c, float kelvin, float tint, float out[3]) {
  if (!has_matrix(c.color_matrix1)) return false;
  double x, y;
  cct_tint_to_xy(kelvin, tint, x, y);
  float XYZ[3] = {float(x / y), 1.f, float((1.0 - x - y) / y)};
  Mat3 cm = color_matrix_at(c, kelvin);
  float n[3];
  cm.apply(XYZ, n);
  if (n[1] <= 0) return false;
  out[0] = n[0] / n[1];
  out[1] = 1.f;
  out[2] = n[2] / n[1];
  return out[0] > 0 && out[2] > 0;
}

float estimate_cct_from_neutral(const LumenColorInfo& c, const float neutral[3]) {
  if (!has_matrix(c.color_matrix1) || neutral[1] <= 0) return 0.f;
  const float nr = neutral[0] / neutral[1], nb = neutral[2] / neutral[1];
  auto err = [&](float T) {
    float n[3];
    if (!camera_neutral_for_cct(c, T, 0.f, n)) return 1e9f;
    float a = std::log(n[0] / nr), b = std::log(n[2] / nb);
    return a * a + b * b;
  };
  // coarse log-spaced scan then golden-section refinement
  float bestT = 5000.f, best = 1e30f;
  for (int i = 0; i <= 60; ++i) {
    float T = 2000.f * std::pow(12000.f / 2000.f, float(i) / 60.f);
    float e = err(T);
    if (e < best) {
      best = e;
      bestT = T;
    }
  }
  float a = bestT / 1.04f, b = bestT * 1.04f;
  const float g = 0.6180339887f;
  float c1 = b - g * (b - a), c2 = a + g * (b - a);
  float f1 = err(c1), f2 = err(c2);
  for (int it = 0; it < 40; ++it) {
    if (f1 < f2) {
      b = c2; c2 = c1; f2 = f1; c1 = b - g * (b - a); f1 = err(c1);
    } else {
      a = c1; c1 = c2; f1 = f2; c2 = a + g * (b - a); f2 = err(c2);
    }
  }
  return 0.5f * (a + b);
}

static Mat3 bradford(double xs, double ys, double xd, double yd) {
  Mat3 B(kBradford), Bi;
  B.invert(Bi);
  float s[3] = {float(xs / ys), 1.f, float((1 - xs - ys) / ys)};
  float d[3] = {float(xd / yd), 1.f, float((1 - xd - yd) / yd)};
  float rs[3], rd[3];
  B.apply(s, rs);
  B.apply(d, rd);
  Mat3 D = Mat3::diag(rd[0] / rs[0], rd[1] / rs[1], rd[2] / rs[2]);
  return Bi * D * B;
}

static void normalize_rows_to_white(Mat3& m) {
  for (int r = 0; r < 3; ++r) {
    float s = m.m[r * 3] + m.m[r * 3 + 1] + m.m[r * 3 + 2];
    if (std::fabs(s) > 1e-6f)
      for (int c = 0; c < 3; ++c) m.m[r * 3 + c] /= s;
  }
}

bool resolve_color(LumenColorInfo& c) {
  // Neutral that the applied gains correspond to: 1/gains when the platform
  // chose gains (auto or manual Kelvin), else the as-shot neutral.
  float n[3] = {c.as_shot_neutral[0], c.as_shot_neutral[1], c.as_shot_neutral[2]};
  if (c.wb_gains[0] > 0 && c.wb_gains[1] > 0 && c.wb_gains[2] > 0) {
    n[0] = 1.f / c.wb_gains[0];
    n[1] = 1.f / c.wb_gains[1];
    n[2] = 1.f / c.wb_gains[2];
  } else if (!(n[0] > 0 && n[1] > 0 && n[2] > 0)) {
    if (has_matrix(c.color_matrix1)) {
      camera_neutral_for_cct(c, 5500.f, 0.f, n);
    } else {
      n[0] = n[1] = n[2] = 1.f;
    }
  }
  n[0] /= n[1];
  n[2] /= n[1];
  n[1] = 1.f;
  bool have_gains = c.wb_gains[0] > 0 && c.wb_gains[1] > 0 && c.wb_gains[2] > 0;
  if (!have_gains) {
    c.wb_gains[0] = 1.f / n[0];
    c.wb_gains[1] = 1.f;
    c.wb_gains[2] = 1.f / n[2];
  }
  Mat3 cur(c.cam_to_srgb);
  if (!cur.is_zero()) return true;  // platform supplied a matrix (Android CCM)
  if (!has_matrix(c.color_matrix1)) {
    Mat3 I;
    std::memcpy(c.cam_to_srgb, I.m, sizeof(I.m));
    return false;
  }
  const float cct = estimate_cct_from_neutral(c, n);
  Mat3 out;
  if (has_matrix(c.forward_matrix1)) {
    Mat3 fm = has_matrix(c.forward_matrix2)
                  ? lerp(c.forward_matrix1, c.forward_matrix2, matrix_weight(c, cct))
                  : Mat3(c.forward_matrix1);
    out = Mat3(kXYZD50_to_sRGB) * fm;
  } else {
    Mat3 cm = color_matrix_at(c, cct), cmInv;
    if (!cm.invert(cmInv)) return false;
    double wx, wy;
    cct_tint_to_xy(cct > 0 ? cct : 5000.f, 0.f, wx, wy);
    Mat3 adapt = bradford(wx, wy, 0.3127, 0.3290);
    out = Mat3(kXYZD65_to_sRGB) * adapt * cmInv * Mat3::diag(n[0], n[1], n[2]);
  }
  normalize_rows_to_white(out);
  std::memcpy(c.cam_to_srgb, out.m, sizeof(out.m));
  return true;
}

Mat3 srgb_to_p3_matrix() { return Mat3(kXYZD65_to_P3) * Mat3(kSRGB_to_XYZD65); }

}  // namespace lumen
