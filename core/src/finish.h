// Finishing pipeline: Bayer planes -> display-referred RGBA8.
#pragma once
#include "common.h"

namespace lumen {

float srgb_oetf(float x);  // linear -> display (LUT-accelerated)
float srgb_eotf(float v);  // display -> linear

// White-balance + demosaic (MHC) + color matrix into linear RGB in the output
// primaries (sRGB or Display P3). half_size uses 2x2 superpixels.
void build_linear_rgb(const BayerPlanes& planes, const int col[4], const LumenColorInfo& color,
                      bool output_p3, bool half_size, float warmth, RGB& out);

// Full photo rendering. noise_sigma is the residual noise std-dev of the
// merged data at mid-grey (normalized raw units), used for NR/sharpening.
int render_photo(const BayerPlanes& planes, const int col[4], const LumenColorInfo& color,
                 const LumenFinishParams& fp, float noise_sigma, uint8_t* out, int out_stride,
                 float* out_display_sigma = nullptr);

// Display-domain chroma NR + sharpening + 8-bit encode of a display RGB image.
void encode_display(RGB& disp, float chroma_sigma, float sharpen, float sharpen_threshold,
                    bool mono, uint8_t* out, int out_stride);

void upsample_bilinear(const Plane& small, int w, int h, Plane& out);

}  // namespace lumen
