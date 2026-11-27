// DNG writer (TIFF/EP based, little-endian, single strip, uncompressed CFA)
// and a TIFF/DNG metadata + uncompressed pixel reader.
#include "dng.h"

#include <cmath>
#include <cstdio>
#include <ctime>
#include <map>

namespace lumen {

namespace {

enum TType : uint16_t {
  T_BYTE = 1, T_ASCII = 2, T_SHORT = 3, T_LONG = 4, T_RATIONAL = 5, T_SBYTE = 6,
  T_UNDEF = 7, T_SSHORT = 8, T_SLONG = 9, T_SRATIONAL = 10, T_FLOAT = 11, T_DOUBLE = 12
};

struct Entry {
  uint16_t tag;
  uint16_t type;
  uint32_t count;
  std::vector<uint8_t> data;
};

void put16(std::vector<uint8_t>& v, uint16_t x) {
  v.push_back(uint8_t(x & 0xff));
  v.push_back(uint8_t(x >> 8));
}
void put32(std::vector<uint8_t>& v, uint32_t x) {
  for (int i = 0; i < 4; ++i) v.push_back(uint8_t((x >> (8 * i)) & 0xff));
}

struct IFD {
  std::vector<Entry> e;
  void add(uint16_t tag, uint16_t type, uint32_t count, std::vector<uint8_t> data) {
    e.push_back({tag, type, count, std::move(data)});
  }
  void shorts(uint16_t tag, std::initializer_list<uint16_t> vals) {
    std::vector<uint8_t> d;
    for (auto v : vals) put16(d, v);
    add(tag, T_SHORT, uint32_t(vals.size()), d);
  }
  void longs(uint16_t tag, const std::vector<uint32_t>& vals) {
    std::vector<uint8_t> d;
    for (auto v : vals) put32(d, v);
    add(tag, T_LONG, uint32_t(vals.size()), d);
  }
  void bytes(uint16_t tag, const std::vector<uint8_t>& vals, uint16_t type = T_BYTE) {
    add(tag, type, uint32_t(vals.size()), vals);
  }
  void ascii(uint16_t tag, const std::string& s) {
    std::vector<uint8_t> d(s.begin(), s.end());
    d.push_back(0);
    add(tag, T_ASCII, uint32_t(d.size()), d);
  }
  void rationals(uint16_t tag, const std::vector<double>& vals, bool sign) {
    std::vector<uint8_t> d;
    for (double v : vals) {
      uint32_t den = 10000;
      if (std::fabs(v) > 100000.0) den = 1;
      double num = std::round(v * den);
      if (sign) {
        put32(d, uint32_t(int32_t(num)));
      } else {
        put32(d, uint32_t(std::max(0.0, num)));
      }
      put32(d, den);
    }
    add(tag, sign ? T_SRATIONAL : T_RATIONAL, uint32_t(vals.size()), d);
  }
  void exposure(uint16_t tag, double t) {
    std::vector<uint8_t> d;
    if (t > 0 && t < 1.0) {
      put32(d, 1);
      put32(d, uint32_t(std::lround(1.0 / t)));
    } else {
      put32(d, uint32_t(std::lround(t * 1000.0)));
      put32(d, 1000);
    }
    add(tag, T_RATIONAL, 1, d);
  }
  void doubles(uint16_t tag, const std::vector<double>& vals) {
    std::vector<uint8_t> d;
    for (double v : vals) {
      uint64_t u;
      std::memcpy(&u, &v, 8);
      for (int i = 0; i < 8; ++i) d.push_back(uint8_t((u >> (8 * i)) & 0xff));
    }
    add(tag, T_DOUBLE, uint32_t(vals.size()), d);
  }
  void sort() {
    std::sort(e.begin(), e.end(), [](const Entry& a, const Entry& b) { return a.tag < b.tag; });
  }
  uint32_t dir_size() const { return uint32_t(2 + 12 * e.size() + 4); }
  uint32_t data_size() const {
    uint32_t s = 0;
    for (auto& x : e)
      if (x.data.size() > 4) s += uint32_t((x.data.size() + 1) & ~size_t(1));
    return s;
  }
  // Serializes directory + out-of-line data at `base`.
  void write(std::vector<uint8_t>& out, uint32_t base, uint32_t next) const {
    std::vector<uint8_t> dir, extra;
    uint32_t dataOff = base + dir_size();
    put16(dir, uint16_t(e.size()));
    for (auto& x : e) {
      put16(dir, x.tag);
      put16(dir, x.type);
      put32(dir, x.count);
      if (x.data.size() <= 4) {
        std::vector<uint8_t> v = x.data;
        v.resize(4, 0);
        dir.insert(dir.end(), v.begin(), v.end());
      } else {
        put32(dir, dataOff + uint32_t(extra.size()));
        extra.insert(extra.end(), x.data.begin(), x.data.end());
        if (extra.size() & 1) extra.push_back(0);
      }
    }
    put32(dir, next);
    out.insert(out.end(), dir.begin(), dir.end());
    out.insert(out.end(), extra.begin(), extra.end());
  }
  void set_long(uint16_t tag, uint32_t v) {
    for (auto& x : e)
      if (x.tag == tag) {
        x.data.clear();
        put32(x.data, v);
      }
  }
};

std::vector<double> mat(const float* m) {
  std::vector<double> v(9);
  for (int i = 0; i < 9; ++i) v[size_t(i)] = m[i];
  return v;
}

bool nonzero(const float* m, int n) {
  for (int i = 0; i < n; ++i)
    if (m[i] != 0.f) return true;
  return false;
}

}  // namespace

std::vector<uint8_t> build_dng(const DngWriteSpec& s) {
  static const uint8_t patterns[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
  const LumenDngMeta* m = s.meta;
  const LumenColorInfo* c = s.color;
  IFD ifd0, exif;
  const uint32_t W = uint32_t(s.width), H = uint32_t(s.height);
  const uint32_t imageBytes = W * H * 2;

  ifd0.longs(254, {0});
  ifd0.longs(256, {W});
  ifd0.longs(257, {H});
  ifd0.shorts(258, {16});
  ifd0.shorts(259, {1});
  ifd0.shorts(262, {32803});
  ifd0.ascii(271, m && m->make ? m->make : "Lumen");
  ifd0.ascii(272, m && m->model ? m->model : "Lumen Camera");
  ifd0.longs(273, {0});  // patched
  ifd0.shorts(274, {uint16_t(m && m->orientation >= 1 && m->orientation <= 8 ? m->orientation : 1)});
  ifd0.shorts(277, {1});
  ifd0.longs(278, {H});
  ifd0.longs(279, {imageBytes});
  ifd0.shorts(284, {1});
  ifd0.ascii(305, m && m->software ? m->software : "Lumen Camera " LUMEN_CORE_VERSION);
  std::string dt;
  if (m && m->datetime) {
    dt = m->datetime;
  } else {
    char buf[32];
    std::time_t t = std::time(nullptr);
    std::tm tmv{};
#if defined(_WIN32)
    localtime_s(&tmv, &t);
#else
    localtime_r(&t, &tmv);
#endif
    std::strftime(buf, sizeof(buf), "%Y:%m:%d %H:%M:%S", &tmv);
    dt = buf;
  }
  ifd0.ascii(306, dt);
  ifd0.shorts(33421, {2, 2});
  {
    const uint8_t* p = patterns[clampi(s.cfa, 0, 3)];
    ifd0.bytes(33422, {p[0], p[1], p[2], p[3]});
  }
  ifd0.longs(34665, {0});  // EXIF IFD pointer, patched
  ifd0.bytes(50706, {1, 4, 0, 0});
  ifd0.bytes(50707, {1, 1, 0, 0});
  ifd0.ascii(50708, m && m->unique_model ? m->unique_model : (m && m->model ? m->model : "Lumen Camera"));
  ifd0.bytes(50710, {0, 1, 2});
  ifd0.shorts(50711, {1});
  ifd0.shorts(50713, {2, 2});
  ifd0.rationals(50714, {s.black[0], s.black[1], s.black[2], s.black[3]}, false);
  ifd0.longs(50717, {s.white});
  if (c && nonzero(c->color_matrix1, 9)) {
    ifd0.rationals(50721, mat(c->color_matrix1), true);
    if (nonzero(c->color_matrix2, 9)) ifd0.rationals(50722, mat(c->color_matrix2), true);
  } else {
    // identity-ish fallback so readers can still open the file
    ifd0.rationals(50721, {1, 0, 0, 0, 1, 0, 0, 0, 1}, true);
  }
  if (c) {
    float n[3] = {c->as_shot_neutral[0], c->as_shot_neutral[1], c->as_shot_neutral[2]};
    if (!(n[0] > 0 && n[1] > 0 && n[2] > 0) && c->wb_gains[0] > 0 && c->wb_gains[2] > 0) {
      n[0] = c->wb_gains[1] / c->wb_gains[0];
      n[1] = 1.f;
      n[2] = c->wb_gains[1] / c->wb_gains[2];
    }
    if (n[0] > 0 && n[1] > 0 && n[2] > 0) ifd0.rationals(50728, {n[0] / n[1], 1.0, n[2] / n[1]}, false);
    ifd0.rationals(50730, {c->baseline_exposure}, true);
    ifd0.shorts(50778, {uint16_t(c->illuminant1 > 0 ? c->illuminant1 : 21)});
    if (nonzero(c->color_matrix2, 9))
      ifd0.shorts(50779, {uint16_t(c->illuminant2 > 0 ? c->illuminant2 : 21)});
    if (nonzero(c->forward_matrix1, 9)) {
      ifd0.rationals(50964, mat(c->forward_matrix1), true);
      if (nonzero(c->color_matrix2, 9) && nonzero(c->forward_matrix2, 9))
        ifd0.rationals(50965, mat(c->forward_matrix2), true);
    }
  }
  if (s.has_noise) ifd0.doubles(51041, std::vector<double>(s.noise, s.noise + 6));

  // EXIF
  if (m && m->exposure_time_s > 0) exif.exposure(33434, m->exposure_time_s);
  if (m && m->f_number > 0) exif.rationals(33437, {m->f_number}, false);
  if (m && m->iso > 0) exif.shorts(34855, {uint16_t(std::min(65535, m->iso))});
  exif.bytes(36864, {'0', '2', '3', '1'}, T_UNDEF);
  exif.ascii(36867, dt);
  if (m && m->focal_length_mm > 0) exif.rationals(37386, {m->focal_length_mm}, false);

  ifd0.sort();
  exif.sort();
  const uint32_t ifd0Off = 8;
  const uint32_t exifOff = ifd0Off + ifd0.dir_size() + ifd0.data_size();
  uint32_t imgOff = exifOff + exif.dir_size() + exif.data_size();
  imgOff = (imgOff + 15u) & ~15u;
  ifd0.set_long(273, imgOff);
  ifd0.set_long(34665, exifOff);

  std::vector<uint8_t> out;
  out.reserve(size_t(imgOff) + imageBytes);
  out.push_back('I');
  out.push_back('I');
  put16(out, 42);
  put32(out, ifd0Off);
  ifd0.write(out, ifd0Off, 0);
  exif.write(out, exifOff, 0);
  out.resize(imgOff, 0);
  const size_t base = out.size();
  out.resize(base + imageBytes);
  uint8_t* dst = out.data() + base;
  for (size_t i = 0; i < size_t(W) * H; ++i) {
    dst[2 * i] = uint8_t(s.data[i] & 0xff);
    dst[2 * i + 1] = uint8_t(s.data[i] >> 8);
  }
  return out;
}

int write_dng(const DngWriteSpec& spec, const char* path) {
  if (!spec.data || spec.width <= 0 || spec.height <= 0 || !path) return LUMEN_ERR_INVALID_ARG;
  std::vector<uint8_t> bytes = build_dng(spec);
  FILE* f = std::fopen(path, "wb");
  if (!f) return LUMEN_ERR_IO;
  size_t wr = std::fwrite(bytes.data(), 1, bytes.size(), f);
  int rc = std::fclose(f);
  return (wr == bytes.size() && rc == 0) ? LUMEN_OK : LUMEN_ERR_IO;
}

// ---------------------------------------------------------------------------
// Reader
namespace {

struct REntry {
  uint16_t tag, type;
  uint32_t count;
  size_t pos;  // file offset of the value bytes
};

struct Reader {
  const uint8_t* d;
  size_t n;
  bool le = true;
  bool ok = true;
  uint16_t u16(size_t o) {
    if (o + 2 > n) { ok = false; return 0; }
    return le ? uint16_t(d[o] | (d[o + 1] << 8)) : uint16_t((d[o] << 8) | d[o + 1]);
  }
  uint32_t u32(size_t o) {
    if (o + 4 > n) { ok = false; return 0; }
    return le ? uint32_t(d[o] | (d[o + 1] << 8) | (d[o + 2] << 16) | (uint32_t(d[o + 3]) << 24))
              : uint32_t((uint32_t(d[o]) << 24) | (d[o + 1] << 16) | (d[o + 2] << 8) | d[o + 3]);
  }
  static int tsize(uint16_t t) {
    switch (t) {
      case T_BYTE: case T_ASCII: case T_SBYTE: case T_UNDEF: return 1;
      case T_SHORT: case T_SSHORT: return 2;
      case T_LONG: case T_SLONG: case T_FLOAT: return 4;
      case 13: return 4;  // IFD
      case T_RATIONAL: case T_SRATIONAL: case T_DOUBLE: return 8;
      default: return 0;
    }
  }
  std::vector<REntry> ifd(size_t off, uint32_t* next) {
    std::vector<REntry> out;
    uint16_t cnt = u16(off);
    if (!ok || cnt > 4000) { ok = false; return out; }
    for (uint16_t i = 0; i < cnt; ++i) {
      size_t eo = off + 2 + size_t(i) * 12;
      REntry e;
      e.tag = u16(eo);
      e.type = u16(eo + 2);
      e.count = u32(eo + 4);
      size_t bytes = size_t(tsize(e.type)) * e.count;
      e.pos = bytes <= 4 ? eo + 8 : size_t(u32(eo + 8));
      if (bytes > n || e.pos + bytes > n) continue;
      out.push_back(e);
    }
    if (next) *next = u32(off + 2 + size_t(cnt) * 12);
    return out;
  }
  double value(const REntry& e, uint32_t i) {
    size_t p = e.pos + size_t(i) * size_t(tsize(e.type));
    switch (e.type) {
      case T_BYTE: case T_UNDEF: return d[p];
      case T_SBYTE: return int8_t(d[p]);
      case T_SHORT: return u16(p);
      case T_SSHORT: return int16_t(u16(p));
      case T_LONG: case 13: return u32(p);
      case T_SLONG: return int32_t(u32(p));
      case T_RATIONAL: { uint32_t a = u32(p), b = u32(p + 4); return b ? double(a) / b : 0.0; }
      case T_SRATIONAL: { int32_t a = int32_t(u32(p)), b = int32_t(u32(p + 4)); return b ? double(a) / b : 0.0; }
      case T_FLOAT: { uint32_t u = u32(p); float f; std::memcpy(&f, &u, 4); return f; }
      case T_DOUBLE: {
        uint64_t u = 0;
        for (int k = 0; k < 8; ++k) u |= uint64_t(d[p + size_t(le ? k : 7 - k)]) << (8 * k);
        double v; std::memcpy(&v, &u, 8); return v;
      }
      default: return 0.0;
    }
  }
  std::vector<double> values(const REntry& e) {
    std::vector<double> v;
    for (uint32_t i = 0; i < e.count && i < 1024; ++i) v.push_back(value(e, i));
    return v;
  }
  std::string str(const REntry& e) {
    std::string s(reinterpret_cast<const char*>(d + e.pos), e.count);
    auto z = s.find('\0');
    if (z != std::string::npos) s.resize(z);
    return s;
  }
};

struct Parsed {
  std::vector<std::vector<REntry>> ifds;  // all IFDs found
  int raw = -1;                           // index of CFA IFD
};

const REntry* find(const std::vector<REntry>& v, uint16_t tag) {
  for (auto& e : v)
    if (e.tag == tag) return &e;
  return nullptr;
}

bool parse(Reader& r, Parsed& p) {
  if (r.n < 8) return false;
  if (r.d[0] == 'I' && r.d[1] == 'I') r.le = true;
  else if (r.d[0] == 'M' && r.d[1] == 'M') r.le = false;
  else return false;
  if (r.u16(2) != 42) return false;
  std::vector<size_t> queue = {r.u32(4)};
  std::vector<size_t> seen;
  while (!queue.empty() && p.ifds.size() < 64) {
    size_t off = queue.back();
    queue.pop_back();
    if (off == 0 || off >= r.n || std::find(seen.begin(), seen.end(), off) != seen.end()) continue;
    seen.push_back(off);
    uint32_t next = 0;
    auto entries = r.ifd(off, &next);
    if (!r.ok) return false;
    for (auto& e : entries) {
      if (e.tag == 330 || e.tag == 34665) {  // SubIFDs, ExifIFD
        for (uint32_t i = 0; i < e.count && i < 16; ++i) queue.push_back(size_t(r.value(e, i)));
      }
    }
    if (next) queue.push_back(next);
    p.ifds.push_back(std::move(entries));
  }
  for (size_t i = 0; i < p.ifds.size(); ++i) {
    const REntry* pi = find(p.ifds[i], 262);
    if (pi && r.value(*pi, 0) == 32803) {
      p.raw = int(i);
      break;
    }
  }
  return true;
}

const REntry* find_any(const Parsed& p, uint16_t tag) {
  if (p.raw >= 0)
    if (auto e = find(p.ifds[size_t(p.raw)], tag)) return e;
  for (auto& v : p.ifds)
    if (auto e = find(v, tag)) return e;
  return nullptr;
}

}  // namespace

int read_dng_info(const uint8_t* bytes, size_t len, LumenDngInfo& out) {
  std::memset(&out, 0, sizeof(out));
  Reader r{bytes, len};
  Parsed p;
  if (!parse(r, p)) return LUMEN_ERR_INVALID_ARG;
  if (p.raw < 0) return LUMEN_ERR_UNSUPPORTED;
  const auto& R = p.ifds[size_t(p.raw)];
  auto num = [&](const REntry* e, double def) { return e ? r.value(*e, 0) : def; };
  out.raw.width = int32_t(num(find(R, 256), 0));
  out.raw.height = int32_t(num(find(R, 257), 0));
  out.raw.row_stride = out.raw.width * 2;
  const double bps = num(find(R, 258), 16);
  out.compressed = num(find(R, 259), 1) != 1 ? 1 : 0;
  out.raw.white_level = float(num(find_any(p, 50717), std::pow(2.0, bps) - 1.0));
  if (auto e = find_any(p, 50714)) {
    auto v = r.values(*e);
    for (int k = 0; k < 4; ++k) out.raw.black_level[k] = float(v.empty() ? 0.0 : v[size_t(k) % v.size()]);
  }
  out.raw.cfa = LUMEN_CFA_RGGB;
  if (auto e = find(R, 33422)) {
    auto v = r.values(*e);
    if (v.size() >= 4) {
      int a = int(v[0]), b = int(v[1]), c = int(v[2]), d = int(v[3]);
      if (a == 0 && b == 1 && c == 1 && d == 2) out.raw.cfa = LUMEN_CFA_RGGB;
      else if (a == 1 && b == 0 && c == 2 && d == 1) out.raw.cfa = LUMEN_CFA_GRBG;
      else if (a == 1 && b == 2 && c == 0 && d == 1) out.raw.cfa = LUMEN_CFA_GBRG;
      else if (a == 2 && b == 1 && c == 1 && d == 0) out.raw.cfa = LUMEN_CFA_BGGR;
    }
    if (auto dim = find(R, 33421)) {
      auto dv = r.values(*dim);
      if (dv.size() >= 2 && dv[0] == 4 && dv[1] == 4) out.raw.quad_bayer = 1;
    }
  }
  auto m9 = [&](uint16_t tag, float* dst) {
    if (auto e = find_any(p, tag)) {
      auto v = r.values(*e);
      if (v.size() >= 9)
        for (int i = 0; i < 9; ++i) dst[i] = float(v[size_t(i)]);
    }
  };
  m9(50721, out.color.color_matrix1);
  m9(50722, out.color.color_matrix2);
  m9(50964, out.color.forward_matrix1);
  m9(50965, out.color.forward_matrix2);
  out.color.illuminant1 = int32_t(num(find_any(p, 50778), 21));
  out.color.illuminant2 = int32_t(num(find_any(p, 50779), 0));
  if (auto e = find_any(p, 50728)) {
    auto v = r.values(*e);
    if (v.size() >= 3)
      for (int i = 0; i < 3; ++i) out.color.as_shot_neutral[i] = float(v[size_t(i)]);
  }
  out.color.baseline_exposure = float(num(find_any(p, 50730), 0.0));
  if (auto e = find_any(p, 51041)) {
    auto v = r.values(*e);
    if (v.size() >= 2) {
      out.has_noise_profile = 1;
      int col[4];
      cfa_colors(out.raw.cfa, col);
      for (int k = 0; k < 4; ++k) {
        size_t pi = v.size() >= 6 ? size_t(col[k]) * 2 : 0;
        out.raw.noise_scale[k] = float(v[pi]);
        out.raw.noise_offset[k] = float(v[pi + 1]);
      }
    }
  }
  out.exposure_time_s = float(num(find_any(p, 33434), 0));
  out.f_number = float(num(find_any(p, 33437), 0));
  out.focal_length_mm = float(num(find_any(p, 37386), 0));
  out.iso = int32_t(num(find_any(p, 34855), 0));
  out.orientation = int32_t(num(find_any(p, 274), 1));
  out.raw.exposure_time_s = out.exposure_time_s;
  out.raw.iso = float(out.iso);
  if (auto e = find_any(p, 271)) std::snprintf(out.make, sizeof(out.make), "%s", r.str(*e).c_str());
  if (auto e = find_any(p, 272)) std::snprintf(out.model, sizeof(out.model), "%s", r.str(*e).c_str());
  return LUMEN_OK;
}

int read_dng_pixels(const uint8_t* bytes, size_t len, uint16_t* out) {
  Reader r{bytes, len};
  Parsed p;
  if (!parse(r, p) || p.raw < 0) return LUMEN_ERR_INVALID_ARG;
  const auto& R = p.ifds[size_t(p.raw)];
  auto num = [&](const REntry* e, double def) { return e ? r.value(*e, 0) : def; };
  const int W = int(num(find(R, 256), 0)), H = int(num(find(R, 257), 0));
  const int bps = int(num(find(R, 258), 16));
  if (num(find(R, 259), 1) != 1) return LUMEN_ERR_UNSUPPORTED;
  if (W <= 0 || H <= 0 || bps < 8 || bps > 16) return LUMEN_ERR_UNSUPPORTED;
  // Blocks: strips (full width) or tiles.
  struct Block { size_t off; int x, y, w, h; };
  std::vector<Block> blocks;
  if (auto so = find(R, 273)) {
    int rps = int(num(find(R, 278), H));
    if (rps <= 0) rps = H;
    for (uint32_t i = 0; i < so->count; ++i)
      blocks.push_back({size_t(r.value(*so, i)), 0, int(i) * rps, W, std::min(rps, H - int(i) * rps)});
  } else if (auto to = find(R, 324)) {
    int tw = int(num(find(R, 322), 0)), tl = int(num(find(R, 323), 0));
    if (tw <= 0 || tl <= 0) return LUMEN_ERR_UNSUPPORTED;
    int across = (W + tw - 1) / tw;
    for (uint32_t i = 0; i < to->count; ++i)
      blocks.push_back({size_t(r.value(*to, i)), int(i % uint32_t(across)) * tw, int(i / uint32_t(across)) * tl, tw, tl});
  } else {
    return LUMEN_ERR_UNSUPPORTED;
  }
  for (auto& b : blocks) {
    const size_t rowBytes = (size_t(b.w) * size_t(bps) + 7) / 8;
    for (int yy = 0; yy < b.h; ++yy) {
      const int Y = b.y + yy;
      if (Y >= H) break;
      size_t ro = b.off + size_t(yy) * rowBytes;
      if (ro + rowBytes > len) return LUMEN_ERR_IO;
      const uint8_t* s = bytes + ro;
      for (int xx = 0; xx < b.w; ++xx) {
        const int X = b.x + xx;
        if (X >= W) break;
        uint32_t v;
        if (bps == 16) {
          v = r.le ? uint32_t(s[2 * xx] | (s[2 * xx + 1] << 8)) : uint32_t((s[2 * xx] << 8) | s[2 * xx + 1]);
        } else if (bps == 8) {
          v = s[xx];
        } else {  // MSB-first packed bits
          size_t bit = size_t(xx) * size_t(bps);
          v = 0;
          for (int k = 0; k < bps; ++k, ++bit) v = (v << 1) | ((s[bit >> 3] >> (7 - (bit & 7))) & 1u);
        }
        out[size_t(Y) * W + X] = uint16_t(v);
      }
    }
  }
  return LUMEN_OK;
}

}  // namespace lumen
