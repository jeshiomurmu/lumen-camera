// Streaming burst merger (reference selection + align + robust Wiener merge).
#pragma once
#include <vector>

#include "align.h"
#include "common.h"

namespace lumen {

class BurstMerger {
 public:
  BurstMerger(const LumenRawInfo& info, const LumenMergeParams& params);
  int add(const uint16_t* data, int row_stride_bytes);
  int finalize(LumenBurstStats* stats);
  bool finalized() const { return finalized_; }
  const BayerPlanes& result() const { return result_; }
  const LumenRawInfo& info() const { return info_; }
  const int* cfa_col() const { return col_; }
  int frames() const { return merged_; }
  // Residual noise std-dev at mid grey after merging (normalized units).
  float residual_sigma() const;
  void noise_model(float S[4], float O[4]) const;

 private:
  void init_reference();
  void merge_alternate(const std::vector<uint16_t>& frame);
  void to_planes(const std::vector<uint16_t>& frame, BayerPlanes& out) const;

  LumenRawInfo info_;
  LumenMergeParams params_;
  int col_[4];
  float S_[4], O_[4];
  bool noise_known_ = false;
  std::vector<std::vector<uint16_t>> pending_;
  bool ref_ready_ = false;
  BayerPlanes ref_, acc_, result_;
  Pyramid ref_pyr_;
  int merged_ = 0, ref_index_ = 0, seen_ = 0;
  double motion_sum_ = 0;
  long motion_n_ = 0;
  bool finalized_ = false;
};

// Frequency-domain Wiener shrinkage on a plane (noise model S, O).
void spatial_denoise_plane(Plane& p, float S, float O, float strength);

}  // namespace lumen
