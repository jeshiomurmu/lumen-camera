// Camera color science helpers (DNG-style matrices, Kelvin/tint, primaries).
#pragma once
#include "common.h"

namespace lumen {

float illuminant_cct(int exif_light_source);
void cct_tint_to_xy(float kelvin, float tint, double& x, double& y);
// Camera neutral (G normalized to 1) for an illuminant, from ColorMatrix1/2.
bool camera_neutral_for_cct(const LumenColorInfo& c, float kelvin, float tint, float out[3]);
float estimate_cct_from_neutral(const LumenColorInfo& c, const float neutral[3]);
bool resolve_color(LumenColorInfo& c);
Mat3 srgb_to_p3_matrix();
bool has_matrix(const float* m);

}  // namespace lumen
