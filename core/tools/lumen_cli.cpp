// lumen_cli — run the Lumen pipeline on a PC, using uncompressed DNG bursts
// exported from the app (or any camera that writes uncompressed CFA DNGs).
//
//   lumen_cli info  shot.dng
//   lumen_cli merge [--profile natural|vivid|film|mono|night] [--dng merged.dng] out.ppm f1.dng f2.dng ...
//   lumen_cli astro [--mode clip|median|mean|max] [--no-align] [--dark d.dng]... out.ppm l1.dng l2.dng ...
//   lumen_cli lut   LumenLog_to_Rec709.cube
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "lumen_core.h"

static bool read_file(const std::string& p, std::vector<uint8_t>& out) {
  FILE* f = std::fopen(p.c_str(), "rb");
  if (!f) return false;
  std::fseek(f, 0, SEEK_END);
  long n = std::ftell(f);
  std::fseek(f, 0, SEEK_SET);
  out.resize(size_t(n));
  bool ok = std::fread(out.data(), 1, out.size(), f) == out.size();
  std::fclose(f);
  return ok;
}

static bool load_dng(const std::string& p, LumenDngInfo& info, std::vector<uint16_t>& px) {
  std::vector<uint8_t> bytes;
  if (!read_file(p, bytes)) {
    std::fprintf(stderr, "cannot read %s\n", p.c_str());
    return false;
  }
  if (lumen_dng_read_info(bytes.data(), bytes.size(), &info) != LUMEN_OK) {
    std::fprintf(stderr, "%s: not a CFA DNG\n", p.c_str());
    return false;
  }
  if (info.compressed) {
    std::fprintf(stderr, "%s: compressed DNG not supported (export uncompressed)\n", p.c_str());
    return false;
  }
  px.resize(size_t(info.raw.width) * info.raw.height);
  if (lumen_dng_read_pixels(bytes.data(), bytes.size(), px.data()) != LUMEN_OK) {
    std::fprintf(stderr, "%s: cannot decode pixels\n", p.c_str());
    return false;
  }
  return true;
}

static void write_ppm(const std::string& path, const std::vector<uint8_t>& rgba, int w, int h) {
  FILE* f = std::fopen(path.c_str(), "wb");
  if (!f) return;
  std::fprintf(f, "P6\n%d %d\n255\n", w, h);
  for (int i = 0; i < w * h; ++i) std::fwrite(&rgba[size_t(i) * 4], 1, 3, f);
  std::fclose(f);
}

static int profile_of(const std::string& s) {
  if (s == "vivid") return LUMEN_PROFILE_VIVID;
  if (s == "film") return LUMEN_PROFILE_FILM;
  if (s == "mono") return LUMEN_PROFILE_MONO;
  if (s == "night") return LUMEN_PROFILE_NIGHT;
  return LUMEN_PROFILE_NATURAL;
}

int main(int argc, char** argv) {
  if (argc < 3) {
    std::fprintf(stderr, "usage: lumen_cli info|merge|astro|lut ...\n");
    return 2;
  }
  std::string cmd = argv[1];
  if (cmd == "lut") return lumen_write_log_lut(argv[2], 33) == LUMEN_OK ? 0 : 1;
  if (cmd == "info") {
    LumenDngInfo d;
    std::vector<uint16_t> px;
    std::vector<uint8_t> bytes;
    if (!read_file(argv[2], bytes) || lumen_dng_read_info(bytes.data(), bytes.size(), &d) != LUMEN_OK) return 1;
    std::printf("%s %s %dx%d cfa=%d black=%.0f white=%.0f iso=%d exp=%.5fs compressed=%d noise=%d\n", d.make,
                d.model, d.raw.width, d.raw.height, d.raw.cfa, d.raw.black_level[0], d.raw.white_level, d.iso,
                d.exposure_time_s, d.compressed, d.has_noise_profile);
    return 0;
  }
  std::string profile = "natural", dngOut, mode = "clip";
  bool align = true;
  std::vector<std::string> darks, inputs;
  std::string out;
  for (int i = 2; i < argc; ++i) {
    std::string a = argv[i];
    if (a == "--profile" && i + 1 < argc) profile = argv[++i];
    else if (a == "--dng" && i + 1 < argc) dngOut = argv[++i];
    else if (a == "--mode" && i + 1 < argc) mode = argv[++i];
    else if (a == "--dark" && i + 1 < argc) darks.push_back(argv[++i]);
    else if (a == "--no-align") align = false;
    else if (out.empty()) out = a;
    else inputs.push_back(a);
  }
  if (out.empty() || inputs.empty()) {
    std::fprintf(stderr, "need an output and at least one input\n");
    return 2;
  }
  LumenDngInfo first;
  std::vector<uint16_t> px;
  if (!load_dng(inputs[0], first, px)) return 1;
  LumenColorInfo color = first.color;
  LumenDngMeta meta{};
  meta.make = first.make;
  meta.model = first.model;
  meta.exposure_time_s = first.exposure_time_s;
  meta.iso = first.iso;
  meta.f_number = first.f_number;
  meta.focal_length_mm = first.focal_length_mm;
  meta.orientation = first.orientation;
  const int W = first.raw.width, H = first.raw.height;
  std::vector<uint8_t> rgba(size_t(W) * H * 4);

  if (cmd == "merge") {
    LumenBurst* b = lumen_burst_create(&first.raw, nullptr);
    if (!b) return 1;
    for (size_t i = 0; i < inputs.size(); ++i) {
      LumenDngInfo d;
      if (i > 0 && !load_dng(inputs[i], d, px)) return 1;
      lumen_burst_add_frame(b, px.data());
      std::printf("added %s\n", inputs[i].c_str());
    }
    LumenBurstStats st{};
    lumen_burst_finalize(b, &st);
    std::printf("merged %d frames (reference %d, motion %.1f px)\n", st.frames_merged, st.reference_index,
                st.mean_motion_px);
    LumenFinishParams fp;
    lumen_finish_params_for_profile(profile_of(profile), &fp);
    lumen_burst_render(b, &color, &fp, rgba.data(), W * 4);
    write_ppm(out, rgba, W, H);
    if (!dngOut.empty()) lumen_burst_write_dng(b, &color, &meta, dngOut.c_str());
    lumen_burst_destroy(b);
    return 0;
  }
  if (cmd == "astro") {
    LumenAstroParams ap;
    lumen_astro_params_default(&ap);
    ap.align_stars = align ? 1 : 0;
    ap.stack_mode = mode == "median" ? LUMEN_STACK_MEDIAN
                    : mode == "mean" ? LUMEN_STACK_MEAN
                    : mode == "max"  ? LUMEN_STACK_MAXIMUM
                                     : LUMEN_STACK_SIGMA_CLIP;
    ap.temp_dir = ".";
    LumenAstro* a = lumen_astro_create(&first.raw, &ap);
    if (!a) return 1;
    for (auto& dk : darks) {
      LumenDngInfo d;
      std::vector<uint16_t> dp;
      if (load_dng(dk, d, dp)) lumen_astro_add_dark(a, dp.data());
    }
    for (size_t i = 0; i < inputs.size(); ++i) {
      LumenDngInfo d;
      if (i > 0 && !load_dng(inputs[i], d, px)) return 1;
      LumenAstroFrameStats st{};
      int rc = lumen_astro_add_light(a, px.data(), &st);
      std::printf("%s: %s stars=%d matched=%d rot=%.3f rms=%.2f\n", inputs[i].c_str(),
                  rc == LUMEN_OK ? "ok" : "rejected", st.stars_detected, st.stars_matched, st.rotation_deg,
                  st.rms_error_px);
    }
    if (lumen_astro_finalize(a) != LUMEN_OK) return 1;
    lumen_astro_render(a, &color, nullptr, rgba.data(), W * 4);
    write_ppm(out, rgba, W, H);
    if (!dngOut.empty()) lumen_astro_write_dng(a, &color, &meta, dngOut.c_str());
    lumen_astro_destroy(a);
    return 0;
  }
  std::fprintf(stderr, "unknown command %s\n", cmd.c_str());
  return 2;
}
