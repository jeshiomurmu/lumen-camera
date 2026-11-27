// Coarse-to-fine tile alignment (after Hasinoff et al., "Burst photography for
// high dynamic range and low-light imaging on mobile cameras", SIGGRAPH Asia
// 2016). L2 search at coarse levels, L1 at the finest, 3x3 coarse candidates.
#include "align.h"

#include <cmath>
#include <limits>

namespace lumen {

Grid make_grid(int w, int h, int tile) {
  Grid g;
  g.tile = tile;
  g.stride = tile / 2;
  g.origin = -tile / 2;
  g.nx = std::max(1, (w - 1 - g.origin) / g.stride + 1);
  g.ny = std::max(1, (h - 1 - g.origin) / g.stride + 1);
  return g;
}

void build_pyramid(const Plane& base, Pyramid& pyr, int max_levels, int min_size) {
  pyr.lv.clear();
  pyr.lv.push_back(base);
  while (int(pyr.lv.size()) < max_levels) {
    const Plane& last = pyr.lv.back();
    if ((last.w + 1) / 2 < min_size || (last.h + 1) / 2 < min_size) break;
    Plane next;
    downsample2(last, next);
    pyr.lv.push_back(std::move(next));
  }
}

static inline void load_tile(const Plane& p, int x0, int y0, int T, float* buf) {
  if (x0 >= 0 && y0 >= 0 && x0 + T <= p.w && y0 + T <= p.h) {
    for (int y = 0; y < T; ++y) std::memcpy(buf + y * T, p.row(y0 + y) + x0, sizeof(float) * size_t(T));
  } else {
    for (int y = 0; y < T; ++y)
      for (int x = 0; x < T; ++x) buf[y * T + x] = p.atc(x0 + x, y0 + y);
  }
}

static inline float tile_error(const Plane& alt, const float* ref, int T, int ax, int ay, bool l1,
                               float bound) {
  float e = 0.f;
  if (ax >= 0 && ay >= 0 && ax + T <= alt.w && ay + T <= alt.h) {
    for (int y = 0; y < T; ++y) {
      const float* a = alt.row(ay + y) + ax;
      const float* r = ref + y * T;
      if (l1) {
        for (int x = 0; x < T; ++x) e += std::fabs(r[x] - a[x]);
      } else {
        for (int x = 0; x < T; ++x) {
          float d = r[x] - a[x];
          e += d * d;
        }
      }
      if (e > bound) return e;  // early out
    }
  } else {
    for (int y = 0; y < T; ++y) {
      for (int x = 0; x < T; ++x) {
        float d = ref[y * T + x] - alt.atc(ax + x, ay + y);
        e += l1 ? std::fabs(d) : d * d;
      }
      if (e > bound) return e;
    }
  }
  return e;
}

void align_pyramids(const Pyramid& ref, const Pyramid& alt, int finest_radius, Grid& grid0,
                    Offsets& out) {
  const int L = int(std::min(ref.lv.size(), alt.lv.size()));
  std::vector<Grid> grids(static_cast<size_t>(L));
  std::vector<Offsets> offs(static_cast<size_t>(L));

  for (int l = L - 1; l >= 0; --l) {
    const Plane& R = ref.lv[size_t(l)];
    const Plane& A = alt.lv[size_t(l)];
    const int T = (l == 0) ? 16 : 8;
    Grid g = make_grid(R.w, R.h, T);
    grids[size_t(l)] = g;
    Offsets& o = offs[size_t(l)];
    o.nx = g.nx;
    o.ny = g.ny;
    o.dx.assign(size_t(g.nx) * g.ny, 0);
    o.dy.assign(size_t(g.nx) * g.ny, 0);
    const int radius = (l == 0) ? std::max(1, finest_radius) : 4;
    const bool l1 = (l == 0);
    const bool coarsest = (l == L - 1);

    parallel_for(g.ny, [&](int ty0, int ty1) {
      std::vector<float> rbuf(size_t(T) * T);
      int cand[18];
      for (int ty = ty0; ty < ty1; ++ty) {
        for (int tx = 0; tx < g.nx; ++tx) {
          const int x0 = g.x0(tx), y0 = g.y0(ty);
          load_tile(R, x0, y0, T, rbuf.data());
          int nc = 0;
          if (coarsest) {
            cand[0] = 0;
            cand[1] = 0;
            nc = 1;
          } else {
            const Grid& gc = grids[size_t(l + 1)];
            const Offsets& oc = offs[size_t(l + 1)];
            const float cx = (float(x0) + 0.5f * T) * 0.5f;
            const float cy = (float(y0) + 0.5f * T) * 0.5f;
            const int tcx = int(std::lround(cx / float(gc.stride)));
            const int tcy = int(std::lround(cy / float(gc.stride)));
            for (int j = -1; j <= 1; ++j) {
              for (int i = -1; i <= 1; ++i) {
                int qx = clampi(tcx + i, 0, gc.nx - 1), qy = clampi(tcy + j, 0, gc.ny - 1);
                int cdx = 2 * oc.dx[size_t(oc.idx(qx, qy))];
                int cdy = 2 * oc.dy[size_t(oc.idx(qx, qy))];
                bool dup = false;
                for (int c = 0; c < nc; ++c)
                  if (cand[2 * c] == cdx && cand[2 * c + 1] == cdy) dup = true;
                if (!dup) {
                  cand[2 * nc] = cdx;
                  cand[2 * nc + 1] = cdy;
                  ++nc;
                }
              }
            }
          }
          float best = std::numeric_limits<float>::max();
          int bdx = 0, bdy = 0;
          for (int c = 0; c < nc; ++c) {
            float e = tile_error(A, rbuf.data(), T, x0 + cand[2 * c], y0 + cand[2 * c + 1], l1, best);
            if (e < best) {
              best = e;
              bdx = cand[2 * c];
              bdy = cand[2 * c + 1];
            }
          }
          const int cx0 = bdx, cy0 = bdy;
          for (int dy = -radius; dy <= radius; ++dy) {
            for (int dx = -radius; dx <= radius; ++dx) {
              if (dx == 0 && dy == 0) continue;
              float e = tile_error(A, rbuf.data(), T, x0 + cx0 + dx, y0 + cy0 + dy, l1, best);
              // Prefer the earlier (coarse-predicted) displacement on ties.
              if (e < best * 0.9999f) {
                best = e;
                bdx = cx0 + dx;
                bdy = cy0 + dy;
              }
            }
          }
          o.dx[size_t(o.idx(tx, ty))] = bdx;
          o.dy[size_t(o.idx(tx, ty))] = bdy;
        }
      }
    }, 2);
  }
  grid0 = grids[0];
  out = std::move(offs[0]);
}

}  // namespace lumen
