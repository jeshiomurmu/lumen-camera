// Astrophotography stacker: star detection, triangle matching, similarity
// registration, warping of Bayer planes, sigma-clipped / median / mean / max
// stacking, dark frames, hot-pixel removal and sky-gradient removal.
#pragma once
#include <string>
#include <vector>

#include "common.h"

namespace lumen {

struct Star {
  float x, y, flux;  // full-resolution pixel coordinates
};

struct Similarity {
  // maps reference coordinates -> frame coordinates
  double a = 1, b = 0, tx = 0, ty = 0;
  void apply(double x, double y, double& ox, double& oy) const {
    ox = a * x - b * y + tx;
    oy = b * x + a * y + ty;
  }
};

std::vector<Star> detect_stars(const Plane& gray_half, int max_stars);
int render_astro(const BayerPlanes& planes, const int col[4], const LumenColorInfo& color,
                 const LumenFinishParams* fp, float sigma, bool trails, bool half, uint8_t* out,
                 int out_stride);
// Returns number of inliers (0 on failure).
int register_stars(const std::vector<Star>& ref, const std::vector<Star>& frm, Similarity& out,
                   float* rms);

class AstroStacker {
 public:
  AstroStacker(const LumenRawInfo& info, const LumenAstroParams& params);
  ~AstroStacker();
  int add_dark(const uint16_t* data);
  int add_light(const uint16_t* data, LumenAstroFrameStats* st);
  int finalize();
  int frames() const { return frames_; }
  bool finalized() const { return finalized_; }
  const BayerPlanes& result() const { return result_; }
  void running_mean(BayerPlanes& out) const;
  const LumenRawInfo& info() const { return info_; }
  const int* cfa_col() const { return col_; }
  float residual_sigma() const;

 private:
  void prepare(const uint16_t* data, BayerPlanes& planes) const;
  void cosmetic(BayerPlanes& planes) const;
  void store(const BayerPlanes& warped);
  void remove_gradient(BayerPlanes& planes) const;

  LumenRawInfo info_;
  LumenAstroParams params_;
  std::string temp_dir_;
  int col_[4];
  int w2_, h2_;
  BayerPlanes dark_;
  int darks_ = 0;
  std::vector<Star> ref_stars_;
  bool have_ref_ = false;
  int frames_ = 0;
  // running statistics (always kept, used for mean/max and previews)
  BayerPlanes sum_;
  std::vector<uint16_t> count_[4];
  // frame cache for clip/median
  std::vector<std::string> files_;
  std::vector<std::vector<uint16_t>> mem_frames_;
  bool finalized_ = false;
  BayerPlanes result_;
  float S_ = 0, O_ = 0;
};

}  // namespace lumen
