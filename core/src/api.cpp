// C API glue for the Lumen imaging core.
#include <cmath>
#include <memory>
#include <new>

#include "astro.h"
#include "color.h"
#include "common.h"
#include "dng.h"
#include "finish.h"
#include "merge.h"

using namespace lumen;

struct LumenBurst {
  std::unique_ptr<BurstMerger> m;
  LumenRawInfo info;
  float display_sigma = 0.f;
};

struct LumenAstro {
  std::unique_ptr<AstroStacker> s;
  LumenRawInfo info;
  int mode;
};

namespace {

bool valid_info(const LumenRawInfo* i) {
  return i && i->width > 8 && i->height > 8 && (i->width % 2) == 0 && (i->height % 2) == 0 &&
         i->white_level > i->black_level[0] && i->cfa >= 0 && i->cfa <= 3 &&
         (!i->quad_bayer || ((i->width % 4) == 0 && (i->height % 4) == 0));
}

void planes_to_u16(const BayerPlanes& pl, std::vector<uint16_t>& out) {
  Plane m;
  planes_to_mosaic(pl, m);
  out.resize(m.px.size());
  const float span = 65535.f - 1024.f;
  for (size_t i = 0; i < m.px.size(); ++i)
    out[i] = uint16_t(clampf(std::round(1024.f + m.px[i] * span), 0.f, 65535.f));
}

void noise_rgb(const int col[4], const float S[4], const float O[4], float divisor, double out[6]) {
  double s[3] = {0, 0, 0}, o[3] = {0, 0, 0};
  int n[3] = {0, 0, 0};
  for (int k = 0; k < 4; ++k) {
    s[col[k]] += S[k];
    o[col[k]] += O[k];
    n[col[k]]++;
  }
  for (int c = 0; c < 3; ++c) {
    out[2 * c] = n[c] ? s[c] / n[c] / divisor : 0;
    out[2 * c + 1] = n[c] ? o[c] / n[c] / divisor : 0;
  }
}

int write_planes_dng(const BayerPlanes& pl, const LumenRawInfo& info, const int col[4],
                     const LumenColorInfo* color, const LumenDngMeta* meta, const float S[4],
                     const float O[4], float divisor, const char* path) {
  std::vector<uint16_t> data;
  planes_to_u16(pl, data);
  DngWriteSpec s;
  s.width = pl.w * 2;
  s.height = pl.h * 2;
  s.data = data.data();
  s.cfa = info.cfa;
  for (float& b : s.black) b = 1024.f;
  s.white = 65535;
  LumenColorInfo c{};
  if (color) c = *color;
  s.color = &c;
  s.meta = meta;
  bool haveNoise = false;
  for (int k = 0; k < 4; ++k)
    if (S[k] > 0 || O[k] > 0) haveNoise = true;
  if (haveNoise) {
    s.has_noise = true;
    noise_rgb(col, S, O, divisor, s.noise);
  }
  return write_dng(s, path);
}

}  // namespace

extern "C" {

const char* lumen_core_version(void) { return LUMEN_CORE_VERSION; }

void lumen_merge_params_default(LumenMergeParams* p) {
  if (!p) return;
  p->robustness = 8.f;
  p->spatial_denoise = 0.25f;
  p->reference_mode = 0;
  p->search_radius = 4;
}

LumenBurst* lumen_burst_create(const LumenRawInfo* info, const LumenMergeParams* params) {
  if (!valid_info(info)) return nullptr;
  LumenMergeParams p;
  lumen_merge_params_default(&p);
  if (params) p = *params;
  try {
    auto* b = new LumenBurst();
    b->info = *info;
    b->m.reset(new BurstMerger(*info, p));
    return b;
  } catch (...) {
    return nullptr;
  }
}

int32_t lumen_burst_add_frame(LumenBurst* b, const uint16_t* data) {
  if (!b || !data) return LUMEN_ERR_INVALID_ARG;
  try {
    return b->m->add(data, b->info.row_stride);
  } catch (const std::bad_alloc&) {
    return LUMEN_ERR_NO_MEMORY;
  }
}

int32_t lumen_burst_add_frame_raw10(LumenBurst* b, const uint8_t* data, int32_t row_stride_bytes) {
  if (!b || !data) return LUMEN_ERR_INVALID_ARG;
  try {
    std::vector<uint16_t> tmp(size_t(b->info.width) * b->info.height);
    unpack_raw10(data, b->info.width, b->info.height, row_stride_bytes, tmp.data());
    return b->m->add(tmp.data(), b->info.width * 2);
  } catch (const std::bad_alloc&) {
    return LUMEN_ERR_NO_MEMORY;
  }
}

int32_t lumen_burst_finalize(LumenBurst* b, LumenBurstStats* stats) {
  if (!b) return LUMEN_ERR_INVALID_ARG;
  try {
    return b->m->finalize(stats);
  } catch (const std::bad_alloc&) {
    return LUMEN_ERR_NO_MEMORY;
  }
}

int32_t lumen_burst_get_mosaic(LumenBurst* b, uint16_t* out, int32_t out_stride) {
  if (!b || !out || !b->m->finalized()) return LUMEN_ERR_INVALID_ARG;
  std::vector<uint16_t> d;
  planes_to_u16(b->m->result(), d);
  const int W = b->info.width, H = b->info.height;
  const int stride = out_stride > 0 ? out_stride / 2 : W;
  for (int y = 0; y < H; ++y) std::memcpy(out + size_t(y) * stride, d.data() + size_t(y) * W, size_t(W) * 2);
  return LUMEN_OK;
}

void lumen_burst_destroy(LumenBurst* b) { delete b; }

float lumen_burst_display_sigma(LumenBurst* b) { return b ? b->display_sigma : 0.f; }

void lumen_finish_params_for_profile(int32_t profile, LumenFinishParams* p) {
  if (!p) return;
  *p = LumenFinishParams{};
  p->profile = profile;
  p->auto_exposure = 1;
  switch (profile) {
    case LUMEN_PROFILE_VIVID:
      p->shadows = 0.55f; p->contrast = 0.20f; p->saturation = 0.22f; p->sharpen = 0.80f; p->chroma_nr = 0.6f;
      break;
    case LUMEN_PROFILE_FILM:
      p->shadows = 0.40f; p->contrast = 0.08f; p->saturation = -0.12f; p->sharpen = 0.35f; p->chroma_nr = 0.6f;
      p->warmth = 0.25f;
      break;
    case LUMEN_PROFILE_MONO:
      p->shadows = 0.50f; p->contrast = 0.20f; p->saturation = -1.f; p->sharpen = 0.70f; p->chroma_nr = 0.f;
      break;
    case LUMEN_PROFILE_NIGHT:
      p->shadows = 0.70f; p->contrast = 0.08f; p->saturation = 0.05f; p->sharpen = 0.35f; p->chroma_nr = 0.9f;
      break;
    default:
      p->profile = LUMEN_PROFILE_NATURAL;
      p->shadows = 0.45f; p->contrast = 0.10f; p->saturation = 0.0f; p->sharpen = 0.55f; p->chroma_nr = 0.6f;
      break;
  }
}

void lumen_finish_output_size(const LumenRawInfo* info, int32_t half_size, int32_t* out_w, int32_t* out_h) {
  if (!info) return;
  if (out_w) *out_w = half_size ? info->width / 2 : info->width;
  if (out_h) *out_h = half_size ? info->height / 2 : info->height;
}

int32_t lumen_burst_render(LumenBurst* b, const LumenColorInfo* color, const LumenFinishParams* fp,
                           uint8_t* out_rgba, int32_t out_stride) {
  if (!b || !color || !out_rgba || !b->m->finalized()) return LUMEN_ERR_INVALID_ARG;
  LumenFinishParams p;
  if (fp) p = *fp; else lumen_finish_params_for_profile(LUMEN_PROFILE_NATURAL, &p);
  LumenColorInfo c = *color;
  resolve_color(c);
  const int ow = p.half_size ? b->info.width / 2 : b->info.width;
  try {
    return render_photo(b->m->result(), b->m->cfa_col(), c, p, b->m->residual_sigma(), out_rgba,
                        out_stride > 0 ? out_stride : ow * 4, &b->display_sigma);
  } catch (const std::bad_alloc&) {
    return LUMEN_ERR_NO_MEMORY;
  }
}

int32_t lumen_burst_write_dng(LumenBurst* b, const LumenColorInfo* color, const LumenDngMeta* meta,
                              const char* path) {
  if (!b || !path || !b->m->finalized()) return LUMEN_ERR_INVALID_ARG;
  float S[4], O[4];
  b->m->noise_model(S, O);
  try {
    return write_planes_dng(b->m->result(), b->info, b->m->cfa_col(), color, meta, S, O,
                            float(std::max(1, b->m->frames())), path);
  } catch (const std::bad_alloc&) {
    return LUMEN_ERR_NO_MEMORY;
  }
}

int32_t lumen_render_raw(const LumenRawInfo* info, const uint16_t* data, const LumenColorInfo* color,
                         const LumenFinishParams* fp, uint8_t* out_rgba, int32_t out_stride) {
  if (!valid_info(info) || !data || !color || !out_rgba) return LUMEN_ERR_INVALID_ARG;
  LumenMergeParams mp;
  lumen_merge_params_default(&mp);
  mp.reference_mode = 1;
  mp.spatial_denoise = 0.4f;
  LumenBurst* b = lumen_burst_create(info, &mp);
  if (!b) return LUMEN_ERR_NO_MEMORY;
  int rc = lumen_burst_add_frame(b, data);
  if (rc == LUMEN_OK) rc = lumen_burst_finalize(b, nullptr);
  if (rc == LUMEN_OK) rc = lumen_burst_render(b, color, fp, out_rgba, out_stride);
  lumen_burst_destroy(b);
  return rc;
}

int32_t lumen_write_dng(const LumenRawInfo* info, const uint16_t* data, const LumenColorInfo* color,
                        const LumenDngMeta* meta, const char* path) {
  if (!valid_info(info) || !data || !path) return LUMEN_ERR_INVALID_ARG;
  const int W = info->width, H = info->height;
  std::vector<uint16_t> img(size_t(W) * H);
  const int stride = info->row_stride > 0 ? info->row_stride / 2 : W;
  for (int y = 0; y < H; ++y) std::memcpy(img.data() + size_t(y) * W, data + size_t(y) * stride, size_t(W) * 2);
  if (info->quad_bayer) remosaic_quad_bayer(img, W, H);
  DngWriteSpec s;
  s.width = W;
  s.height = H;
  s.data = img.data();
  s.cfa = info->cfa;
  for (int k = 0; k < 4; ++k) s.black[k] = info->black_level[k];
  s.white = uint32_t(info->white_level);
  LumenColorInfo c{};
  if (color) c = *color;
  s.color = &c;
  s.meta = meta;
  bool haveNoise = false;
  for (int k = 0; k < 4; ++k)
    if (info->noise_scale[k] > 0 || info->noise_offset[k] > 0) haveNoise = true;
  if (haveNoise) {
    int col[4];
    cfa_colors(info->cfa, col);
    s.has_noise = true;
    noise_rgb(col, info->noise_scale, info->noise_offset, 1.f, s.noise);
  }
  return write_dng(s, path);
}

// ---- astro ----------------------------------------------------------------------
void lumen_astro_params_default(LumenAstroParams* p) {
  if (!p) return;
  p->align_stars = 1;
  p->stack_mode = LUMEN_STACK_SIGMA_CLIP;
  p->kappa = 2.5f;
  p->remove_hot_pixels = 1;
  p->remove_gradient = 1;
  p->temp_dir = nullptr;
}

LumenAstro* lumen_astro_create(const LumenRawInfo* info, const LumenAstroParams* params) {
  if (!valid_info(info)) return nullptr;
  LumenAstroParams p;
  lumen_astro_params_default(&p);
  if (params) p = *params;
  try {
    auto* a = new LumenAstro();
    a->info = *info;
    a->mode = p.stack_mode;
    a->s.reset(new AstroStacker(*info, p));
    return a;
  } catch (...) {
    return nullptr;
  }
}

int32_t lumen_astro_add_dark(LumenAstro* a, const uint16_t* data) {
  if (!a || !data) return LUMEN_ERR_INVALID_ARG;
  try { return a->s->add_dark(data); } catch (const std::bad_alloc&) { return LUMEN_ERR_NO_MEMORY; }
}

int32_t lumen_astro_add_light(LumenAstro* a, const uint16_t* data, LumenAstroFrameStats* stats) {
  if (!a || !data) return LUMEN_ERR_INVALID_ARG;
  try { return a->s->add_light(data, stats); } catch (const std::bad_alloc&) { return LUMEN_ERR_NO_MEMORY; }
}

int32_t lumen_astro_frame_count(LumenAstro* a) { return a ? a->s->frames() : 0; }

int32_t lumen_astro_finalize(LumenAstro* a) {
  if (!a) return LUMEN_ERR_INVALID_ARG;
  try { return a->s->finalize(); } catch (const std::bad_alloc&) { return LUMEN_ERR_NO_MEMORY; }
}

int32_t lumen_astro_render(LumenAstro* a, const LumenColorInfo* color, const LumenFinishParams* fp,
                           uint8_t* out_rgba, int32_t out_stride) {
  if (!a || !color || !out_rgba || !a->s->finalized()) return LUMEN_ERR_INVALID_ARG;
  LumenColorInfo c = *color;
  resolve_color(c);
  const bool half = fp && fp->half_size;
  const int ow = half ? a->info.width / 2 : a->info.width;
  try {
    return render_astro(a->s->result(), a->s->cfa_col(), c, fp, a->s->residual_sigma(),
                        a->mode == LUMEN_STACK_MAXIMUM, half, out_rgba, out_stride > 0 ? out_stride : ow * 4);
  } catch (const std::bad_alloc&) {
    return LUMEN_ERR_NO_MEMORY;
  }
}

int32_t lumen_astro_preview(LumenAstro* a, const LumenColorInfo* color, uint8_t* out_rgba, int32_t out_stride) {
  if (!a || !color || !out_rgba || a->s->frames() == 0) return LUMEN_ERR_INVALID_ARG;
  LumenColorInfo c = *color;
  resolve_color(c);
  BayerPlanes pl;
  a->s->running_mean(pl);
  try {
    return render_astro(pl, a->s->cfa_col(), c, nullptr, a->s->residual_sigma(),
                        a->mode == LUMEN_STACK_MAXIMUM, true, out_rgba,
                        out_stride > 0 ? out_stride : (a->info.width / 2) * 4);
  } catch (const std::bad_alloc&) {
    return LUMEN_ERR_NO_MEMORY;
  }
}

int32_t lumen_astro_write_dng(LumenAstro* a, const LumenColorInfo* color, const LumenDngMeta* meta,
                              const char* path) {
  if (!a || !path || !a->s->finalized()) return LUMEN_ERR_INVALID_ARG;
  float S[4], O[4];
  for (int k = 0; k < 4; ++k) {
    S[k] = std::max(0.f, a->info.noise_scale[k]);
    O[k] = std::max(0.f, a->info.noise_offset[k]);
  }
  try {
    return write_planes_dng(a->s->result(), a->info, a->s->cfa_col(), color, meta, S, O,
                            float(std::max(1, a->s->frames())), path);
  } catch (const std::bad_alloc&) {
    return LUMEN_ERR_NO_MEMORY;
  }
}

void lumen_astro_destroy(LumenAstro* a) { delete a; }

// ---- color / dng / misc -------------------------------------------------------------
int32_t lumen_color_resolve(LumenColorInfo* c) {
  if (!c) return LUMEN_ERR_INVALID_ARG;
  return resolve_color(*c) ? LUMEN_OK : LUMEN_ERR_UNSUPPORTED;
}

int32_t lumen_wb_gains_for_cct(const LumenColorInfo* c, float kelvin, float tint, float out_gains[3]) {
  if (!c || !out_gains) return LUMEN_ERR_INVALID_ARG;
  float n[3];
  if (!camera_neutral_for_cct(*c, kelvin, tint, n)) return LUMEN_ERR_UNSUPPORTED;
  out_gains[0] = 1.f / n[0];
  out_gains[1] = 1.f;
  out_gains[2] = 1.f / n[2];
  return LUMEN_OK;
}

float lumen_estimate_cct(const LumenColorInfo* c) {
  if (!c) return 0.f;
  float n[3] = {c->as_shot_neutral[0], c->as_shot_neutral[1], c->as_shot_neutral[2]};
  if (!(n[0] > 0 && n[1] > 0 && n[2] > 0)) {
    if (!(c->wb_gains[0] > 0 && c->wb_gains[1] > 0 && c->wb_gains[2] > 0)) return 0.f;
    n[0] = c->wb_gains[1] / c->wb_gains[0];
    n[1] = 1.f;
    n[2] = c->wb_gains[1] / c->wb_gains[2];
  }
  return estimate_cct_from_neutral(*c, n);
}

int32_t lumen_dng_read_info(const uint8_t* bytes, size_t len, LumenDngInfo* out) {
  if (!bytes || !out) return LUMEN_ERR_INVALID_ARG;
  return read_dng_info(bytes, len, *out);
}

int32_t lumen_dng_read_pixels(const uint8_t* bytes, size_t len, uint16_t* out) {
  if (!bytes || !out) return LUMEN_ERR_INVALID_ARG;
  return read_dng_pixels(bytes, len, out);
}

int32_t lumen_estimate_noise(LumenRawInfo* info, const uint16_t* data) {
  if (!valid_info(info) || !data) return LUMEN_ERR_INVALID_ARG;
  BayerPlanes p;
  extract_planes(*info, data, p);
  estimate_noise_planes(p, info->noise_scale, info->noise_offset);
  return LUMEN_OK;
}

float lumen_sharpness(const LumenRawInfo* info, const uint16_t* data) {
  if (!valid_info(info) || !data) return 0.f;
  BayerPlanes p;
  extract_planes(*info, data, p);
  Plane g, g2;
  planes_to_gray(p, g);
  downsample2(g, g2);
  return laplacian_variance(g2);
}

}  // extern "C"
