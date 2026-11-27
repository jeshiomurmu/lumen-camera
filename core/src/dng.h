// Minimal DNG 1.4 writer (uncompressed 16-bit CFA) and TIFF/DNG reader.
#pragma once
#include <string>
#include <vector>

#include "common.h"

namespace lumen {

struct DngWriteSpec {
  int width = 0, height = 0;
  const uint16_t* data = nullptr;  // contiguous width*height samples
  int cfa = LUMEN_CFA_RGGB;
  float black[4] = {0, 0, 0, 0};
  uint32_t white = 65535;
  const LumenColorInfo* color = nullptr;
  const LumenDngMeta* meta = nullptr;
  bool has_noise = false;
  double noise[6] = {0, 0, 0, 0, 0, 0};  // S,O for R,G,B
};

int write_dng(const DngWriteSpec& spec, const char* path);
std::vector<uint8_t> build_dng(const DngWriteSpec& spec);

int read_dng_info(const uint8_t* bytes, size_t len, LumenDngInfo& out);
int read_dng_pixels(const uint8_t* bytes, size_t len, uint16_t* out);

}  // namespace lumen
