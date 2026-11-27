// HDR+-style coarse-to-fine tile alignment on a grayscale pyramid.
#pragma once
#include <vector>

#include "common.h"

namespace lumen {

struct Pyramid {
  std::vector<Plane> lv;  // lv[0] = finest
};

// Tiles of size `tile` placed every tile/2 starting at -tile/2, so every pixel
// is covered by exactly 2x2 tiles whose raised-cosine windows sum to one.
struct Grid {
  int tile = 16, stride = 8, origin = -8, nx = 0, ny = 0;
  int x0(int tx) const { return origin + tx * stride; }
  int y0(int ty) const { return origin + ty * stride; }
};

struct Offsets {
  int nx = 0, ny = 0;
  std::vector<int> dx, dy;
  int idx(int tx, int ty) const { return ty * nx + tx; }
};

Grid make_grid(int w, int h, int tile);
void build_pyramid(const Plane& base, Pyramid& pyr, int max_levels = 5, int min_size = 40);
// Aligns `alt` to `ref`; offsets are for the level-0 grid (tile 16).
void align_pyramids(const Pyramid& ref, const Pyramid& alt, int finest_radius, Grid& grid0,
                    Offsets& out);

}  // namespace lumen
