// Robust burst merge in the frequency domain (pairwise Wiener filter against
// the reference, per Bayer plane, 16x16 tiles with 50% overlap), after
// Hasinoff et al. 2016. Merging is streaming: memory does not grow with N.
#include "merge.h"

#include <cmath>

#include "fft16.h"

namespace lumen {

BurstMerger::BurstMerger(const LumenRawInfo& info, const LumenMergeParams& params)
    : info_(info), params_(params) {
  cfa_colors(info.cfa, col_);
  noise_known_ = true;
  for (int k = 0; k < 4; ++k) {
    S_[k] = info.noise_scale[k];
    O_[k] = info.noise_offset[k];
    if (!(S_[k] > 0.f) && !(O_[k] > 0.f)) noise_known_ = false;
  }
  if (params_.robustness <= 0.f) params_.robustness = 8.f;
  if (params_.search_radius <= 0) params_.search_radius = 4;
}

void BurstMerger::to_planes(const std::vector<uint16_t>& frame, BayerPlanes& out) const {
  LumenRawInfo inf = info_;
  inf.row_stride = info_.width * 2;
  extract_planes(inf, frame.data(), out);
}

int BurstMerger::add(const uint16_t* data, int row_stride_bytes) {
  if (finalized_) return LUMEN_ERR_STATE;
  const int W = info_.width, H = info_.height;
  std::vector<uint16_t> frame(size_t(W) * H);
  const int stride = row_stride_bytes > 0 ? row_stride_bytes / 2 : W;
  for (int y = 0; y < H; ++y)
    std::memcpy(frame.data() + size_t(y) * W, data + size_t(y) * stride, sizeof(uint16_t) * size_t(W));
  if (info_.quad_bayer) remosaic_quad_bayer(frame, W, H);
  ++seen_;
  if (!ref_ready_) {
    pending_.push_back(std::move(frame));
    const size_t need = params_.reference_mode == 1 ? 1 : 3;
    if (pending_.size() >= need) init_reference();
    return LUMEN_OK;
  }
  merge_alternate(frame);
  return LUMEN_OK;
}

void BurstMerger::init_reference() {
  if (pending_.empty()) return;
  int best = 0;
  if (params_.reference_mode != 1 && pending_.size() > 1) {
    float bestScore = -1.f;
    for (size_t i = 0; i < pending_.size(); ++i) {
      BayerPlanes pl;
      to_planes(pending_[i], pl);
      Plane g, g2;
      planes_to_gray(pl, g);
      downsample2(g, g2);  // suppress noise before the Laplacian
      float sc = laplacian_variance(g2);
      if (sc > bestScore) {
        bestScore = sc;
        best = int(i);
      }
    }
  }
  ref_index_ = best;
  to_planes(pending_[size_t(best)], ref_);
  if (!noise_known_) {
    estimate_noise_planes(ref_, S_, O_);
    noise_known_ = true;
  }
  Plane gray;
  planes_to_gray(ref_, gray);
  build_pyramid(gray, ref_pyr_);
  acc_ = ref_;  // windows form a partition of unity: overlap-add(ref) == ref
  merged_ = 1;
  ref_ready_ = true;
  std::vector<std::vector<uint16_t>> rest;
  for (size_t i = 0; i < pending_.size(); ++i)
    if (int(i) != best) rest.push_back(std::move(pending_[i]));
  pending_.clear();
  for (auto& f : rest) merge_alternate(f);
}

static void merge_plane(const Plane& ref, const Plane& alt, const Offsets& off, const Grid& g,
                        float S, float O, float c, Plane& acc) {
  const FFT16& F = fft16();
  for (int parity = 0; parity < 2; ++parity) {
    const int rows = (g.ny - parity + 1) / 2;
    parallel_for(rows, [&](int r0, int r1) {
      alignas(16) float rre[256], rim[256], are[256], aim[256];
      for (int r = r0; r < r1; ++r) {
        const int ty = parity + 2 * r;
        const int y0 = g.y0(ty);
        for (int tx = 0; tx < g.nx; ++tx) {
          const int x0 = g.x0(tx);
          const size_t ti = size_t(off.idx(tx, ty));
          const int dx = off.dx[ti];
          const int dy = off.dy[ti];
          // Integer offsets at plane resolution keep the Bayer phase intact;
          // residual sub-pixel error is handled by the robust Wiener weights.
          const bool inR = x0 >= 0 && y0 >= 0 && x0 + 16 <= ref.w && y0 + 16 <= ref.h;
          const bool inA = x0 + dx >= 0 && y0 + dy >= 0 && x0 + dx + 16 <= alt.w && y0 + dy + 16 <= alt.h;
          double mean = 0;
          for (int y = 0; y < 16; ++y) {
            for (int x = 0; x < 16; ++x) {
              const int i = y * 16 + x;
              const float rv = inR ? ref.at(x0 + x, y0 + y) : ref.atc(x0 + x, y0 + y);
              const float av = inA ? alt.at(x0 + dx + x, y0 + dy + y) : alt.atc(x0 + dx + x, y0 + dy + y);
              mean += rv;
              rre[i] = rv * F.win[i];
              are[i] = av * F.win[i];
              rim[i] = 0.f;
              aim[i] = 0.f;
            }
          }
          mean /= 256.0;
          const float var = std::max(S * float(std::max(mean, 0.0)) + O, 1e-12f) * F.win2sum;
          const float cs = c * var;
          F.fft2d(rre, rim, false);
          F.fft2d(are, aim, false);
          for (int i = 0; i < 256; ++i) {
            const float dr = rre[i] - are[i], di = rim[i] - aim[i];
            const float d2 = dr * dr + di * di;
            const float A = d2 / (d2 + cs);
            are[i] += A * dr;
            aim[i] += A * di;
          }
          F.fft2d(are, aim, true);
          for (int y = 0; y < 16; ++y) {
            const int Y = y0 + y;
            if (Y < 0 || Y >= acc.h) continue;
            float* row = acc.row(Y);
            for (int x = 0; x < 16; ++x) {
              const int X = x0 + x;
              if (X < 0 || X >= acc.w) continue;
              row[X] += are[y * 16 + x] * (1.f / 256.f);
            }
          }
        }
      }
    }, 1);
  }
}

void BurstMerger::merge_alternate(const std::vector<uint16_t>& frame) {
  BayerPlanes alt;
  to_planes(frame, alt);
  Plane gray;
  planes_to_gray(alt, gray);
  Pyramid pyr;
  build_pyramid(gray, pyr);
  Grid g;
  Offsets off;
  align_pyramids(ref_pyr_, pyr, params_.search_radius, g, off);
  for (size_t i = 0; i < off.dx.size(); ++i) {
    motion_sum_ += std::sqrt(double(off.dx[i] * off.dx[i] + off.dy[i] * off.dy[i])) * 2.0;
    ++motion_n_;
  }
  for (int k = 0; k < 4; ++k)
    merge_plane(ref_.p[k], alt.p[k], off, g, S_[k], O_[k], params_.robustness, acc_.p[k]);
  ++merged_;
}

void spatial_denoise_plane(Plane& p, float S, float O, float strength) {
  if (strength <= 0.f) return;
  const FFT16& F = fft16();
  Grid g = make_grid(p.w, p.h, 16);
  Plane out(p.w, p.h, 0.f);
  float freqw[256];
  for (int v = 0; v < 16; ++v)
    for (int u = 0; u < 16; ++u) {
      float fu = float(std::min(u, 16 - u)) / 8.f, fv = float(std::min(v, 16 - v)) / 8.f;
      float fr = std::sqrt(fu * fu + fv * fv) / 1.41421356f;  // 0..1
      freqw[v * 16 + u] = (u == 0 && v == 0) ? 0.f : strength * (0.25f + 1.75f * fr);
    }
  for (int parity = 0; parity < 2; ++parity) {
    const int rows = (g.ny - parity + 1) / 2;
    parallel_for(rows, [&](int r0, int r1) {
      alignas(16) float re[256], im[256];
      for (int r = r0; r < r1; ++r) {
        const int ty = parity + 2 * r;
        const int y0 = g.y0(ty);
        for (int tx = 0; tx < g.nx; ++tx) {
          const int x0 = g.x0(tx);
          double mean = 0;
          for (int y = 0; y < 16; ++y)
            for (int x = 0; x < 16; ++x) {
              float v = p.atc(x0 + x, y0 + y);
              mean += v;
              re[y * 16 + x] = v * F.win[y * 16 + x];
              im[y * 16 + x] = 0.f;
            }
          mean /= 256.0;
          const float var = std::max(S * float(std::max(mean, 0.0)) + O, 1e-12f) * F.win2sum;
          F.fft2d(re, im, false);
          for (int i = 0; i < 256; ++i) {
            const float k = freqw[i] * var;
            if (k <= 0.f) continue;
            const float m2 = re[i] * re[i] + im[i] * im[i];
            const float G = m2 / (m2 + k);
            re[i] *= G;
            im[i] *= G;
          }
          F.fft2d(re, im, true);
          for (int y = 0; y < 16; ++y) {
            const int Y = y0 + y;
            if (Y < 0 || Y >= p.h) continue;
            float* row = out.row(Y);
            for (int x = 0; x < 16; ++x) {
              const int X = x0 + x;
              if (X < 0 || X >= p.w) continue;
              row[X] += re[y * 16 + x] * (1.f / 256.f);
            }
          }
        }
      }
    }, 1);
  }
  p = std::move(out);
}

int BurstMerger::finalize(LumenBurstStats* stats) {
  if (finalized_) return LUMEN_ERR_STATE;
  if (!ref_ready_) init_reference();
  if (!ref_ready_) return LUMEN_ERR_STATE;
  result_ = acc_;
  const float inv = 1.f / float(merged_);
  for (int k = 0; k < 4; ++k)
    for (float& v : result_.p[k].px) v *= inv;
  acc_ = BayerPlanes();
  ref_ = BayerPlanes();
  ref_pyr_ = Pyramid();
  if (params_.spatial_denoise > 0.f) {
    for (int k = 0; k < 4; ++k)
      spatial_denoise_plane(result_.p[k], S_[k] * inv, O_[k] * inv, params_.spatial_denoise);
  }
  finalized_ = true;
  if (stats) {
    stats->frames_merged = merged_;
    stats->reference_index = ref_index_;
    stats->mean_motion_px = motion_n_ ? float(motion_sum_ / double(motion_n_)) : 0.f;
    stats->noise_scale = 0.25f * (S_[0] + S_[1] + S_[2] + S_[3]);
    stats->noise_offset = 0.25f * (O_[0] + O_[1] + O_[2] + O_[3]);
  }
  return LUMEN_OK;
}

float BurstMerger::residual_sigma() const {
  float S = 0.25f * (S_[0] + S_[1] + S_[2] + S_[3]);
  float O = 0.25f * (O_[0] + O_[1] + O_[2] + O_[3]);
  float n = float(std::max(1, merged_));
  float spatial = 1.f + 2.f * std::max(0.f, params_.spatial_denoise);
  return std::sqrt(std::max(0.f, (S * 0.18f + O) / (n * spatial)));
}

void BurstMerger::noise_model(float S[4], float O[4]) const {
  for (int k = 0; k < 4; ++k) {
    S[k] = S_[k];
    O[k] = O_[k];
  }
}

}  // namespace lumen
