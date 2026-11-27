/*
 * Lumen Imaging Core — public C API
 *
 * One portable C++17 engine shared by the Android app (via JNI) and the iOS
 * app (via the Swift bridging header). Everything here is plain C so both
 * platforms can call it without wrappers.
 *
 *   Burst   : HDR+-style tile alignment + robust frequency-domain merge of a
 *             burst of RAW Bayer frames (photo, night).
 *   Finish  : white balance, demosaic, color matrix, local tone mapping,
 *             chroma NR, sharpening, color profiles -> 8-bit RGBA.
 *   Astro   : star detection, triangle-matching registration, sigma-clipped
 *             stacking, dark frames, hot pixels, gradient removal, stretch.
 *   DNG     : writes 16-bit CFA DNG 1.4 files; reads DNG/TIFF metadata.
 *   Color   : Kelvin/tint -> WB gains from DNG-style matrices.
 *
 * Conventions
 *   - CFA "position" k = (y & 1) * 2 + (x & 1): 0=(0,0) 1=(1,0) 2=(0,1) 3=(1,1)
 *   - Normalized signal x = (raw - black[k]) / (white - black[k])
 *   - Noise model: variance(x) = scale[k] * x + offset[k]   (Android
 *     SENSOR_NOISE_PROFILE / DNG NoiseProfile convention)
 *   - Matrices are row-major 3x3 float[9].
 *
 * License: Apache-2.0 (see LICENSE in the repository root).
 */
#ifndef LUMEN_CORE_H
#define LUMEN_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define LUMEN_CORE_VERSION "1.0.0"

/* ---- status codes ---------------------------------------------------- */
#define LUMEN_OK 0
#define LUMEN_ERR_INVALID_ARG (-1)
#define LUMEN_ERR_NO_MEMORY (-2)
#define LUMEN_ERR_IO (-3)
#define LUMEN_ERR_STATE (-4)
#define LUMEN_ERR_NOT_ENOUGH_STARS (-5)
#define LUMEN_ERR_UNSUPPORTED (-6)

typedef enum {
  LUMEN_CFA_RGGB = 0,
  LUMEN_CFA_GRBG = 1,
  LUMEN_CFA_GBRG = 2,
  LUMEN_CFA_BGGR = 3
} LumenCfa;

/* Describes one RAW Bayer frame (all frames of a burst share it). */
typedef struct {
  int32_t width;          /* pixels, even */
  int32_t height;         /* pixels, even */
  int32_t row_stride;     /* bytes per row of the uint16 input buffer */
  int32_t cfa;            /* LumenCfa */
  int32_t quad_bayer;     /* 1 = 2x2-binned "Quad Bayer" CFA (remosaiced) */
  float black_level[4];   /* per CFA position k */
  float white_level;      /* saturation level in raw units */
  float noise_scale[4];   /* per position; <= 0 means "estimate from data" */
  float noise_offset[4];
  float exposure_time_s;  /* informational */
  float iso;              /* informational */
} LumenRawInfo;

/* Color description of the camera for finishing and DNG writing. */
typedef struct {
  float wb_gains[3];        /* R,G,B gains applied to camera RGB (G = 1) */
  float cam_to_srgb[9];     /* white-balanced camera RGB -> linear sRGB D65.
                               All zeros => derive from the DNG matrices. */
  float color_matrix1[9];   /* DNG ColorMatrix1 (XYZ -> camera), zeros = absent */
  float color_matrix2[9];
  float forward_matrix1[9]; /* DNG ForwardMatrix1, zeros = absent */
  float forward_matrix2[9];
  int32_t illuminant1;      /* EXIF LightSource code (17=Std A, 21=D65, ...) */
  int32_t illuminant2;
  float as_shot_neutral[3]; /* camera-space neutral, e.g. 1/gains */
  float baseline_exposure;  /* EV */
} LumenColorInfo;

/* Metadata copied into DNG files. Strings may be NULL. */
typedef struct {
  const char* make;
  const char* model;
  const char* unique_model;
  const char* software;
  const char* datetime;     /* "YYYY:MM:DD HH:MM:SS" */
  int32_t orientation;      /* TIFF orientation 1..8 */
  float exposure_time_s;
  float f_number;
  float focal_length_mm;
  int32_t iso;
} LumenDngMeta;

/* ---- burst merge ------------------------------------------------------ */
typedef struct LumenBurst LumenBurst;

typedef struct {
  float robustness;       /* Wiener constant c; higher = more ghost-safe (8) */
  float spatial_denoise;  /* 0..1 extra frequency-domain spatial NR on merge */
  int32_t reference_mode; /* 0 = sharpest of first 3 frames, 1 = first */
  int32_t search_radius;  /* finest-level search radius in half-res px (2) */
} LumenMergeParams;

typedef struct {
  int32_t frames_merged;
  int32_t reference_index;
  float mean_motion_px;   /* mean |offset| in full-res pixels */
  float noise_scale;      /* noise model used (plane-average) */
  float noise_offset;
} LumenBurstStats;

void lumen_merge_params_default(LumenMergeParams* p);

/* Creates a burst. max_frames bounds internal buffering (>= 1). */
LumenBurst* lumen_burst_create(const LumenRawInfo* info,
                               const LumenMergeParams* params);
/* Adds one frame (uint16 samples, row_stride bytes per row). Frames are
 * aligned and merged as they arrive, so memory stays bounded. */
int32_t lumen_burst_add_frame(LumenBurst* b, const uint16_t* data);
/* Adds a RAW10 (MIPI packed, 4 px in 5 bytes) frame. */
int32_t lumen_burst_add_frame_raw10(LumenBurst* b, const uint8_t* data,
                                    int32_t row_stride_bytes);
/* Finishes merging. Must be called once before finish/write. */
int32_t lumen_burst_finalize(LumenBurst* b, LumenBurstStats* stats);
/* Copies the merged Bayer mosaic as uint16 with a 1024 pedestal and
 * white level 65535 (what lumen_burst_write_dng stores). */
int32_t lumen_burst_get_mosaic(LumenBurst* b, uint16_t* out, int32_t out_stride);
void lumen_burst_destroy(LumenBurst* b);

/* ---- finishing ------------------------------------------------------- */
typedef enum {
  LUMEN_PROFILE_NATURAL = 0,
  LUMEN_PROFILE_VIVID = 1,
  LUMEN_PROFILE_FILM = 2,
  LUMEN_PROFILE_MONO = 3,
  LUMEN_PROFILE_NIGHT = 4
} LumenProfile;

typedef struct {
  int32_t profile;        /* LumenProfile */
  float exposure_ev;      /* additional exposure */
  int32_t auto_exposure;  /* 1 = scene-key auto brightness */
  float shadows;          /* 0..1 local tone mapping (shadow lift) */
  float contrast;         /* -0.5..0.5 */
  float saturation;       /* -1..1 (0 = accurate) */
  float sharpen;          /* 0..2 */
  float chroma_nr;        /* 0..1 */
  float warmth;           /* -1..1 creative WB shift */
  int32_t output_p3;      /* 1 = Display P3 primaries, 0 = sRGB */
  int32_t half_size;      /* 1 = fast half-resolution output */
} LumenFinishParams;

void lumen_finish_params_for_profile(int32_t profile, LumenFinishParams* p);

/* Output size of finish for a frame (accounts for half_size). */
void lumen_finish_output_size(const LumenRawInfo* info, int32_t half_size,
                              int32_t* out_w, int32_t* out_h);

/* Renders the merged burst to RGBA8888 (out_stride bytes per row). */
int32_t lumen_burst_render(LumenBurst* b, const LumenColorInfo* color,
                           const LumenFinishParams* fp, uint8_t* out_rgba,
                           int32_t out_stride);
/* Residual noise std-dev of the last render in display units (0..1); feed it
 * to the AI denoiser as its noise level. */
float lumen_burst_display_sigma(LumenBurst* b);
/* Writes the merged burst as a DNG (more bits than any single frame). */
int32_t lumen_burst_write_dng(LumenBurst* b, const LumenColorInfo* color,
                              const LumenDngMeta* meta, const char* path);

/* Single-frame helpers (no merge). */
int32_t lumen_render_raw(const LumenRawInfo* info, const uint16_t* data,
                         const LumenColorInfo* color,
                         const LumenFinishParams* fp, uint8_t* out_rgba,
                         int32_t out_stride);
int32_t lumen_write_dng(const LumenRawInfo* info, const uint16_t* data,
                        const LumenColorInfo* color, const LumenDngMeta* meta,
                        const char* path);

/* ---- astro ----------------------------------------------------------- */
typedef struct LumenAstro LumenAstro;

typedef enum {
  LUMEN_STACK_SIGMA_CLIP = 0, /* kappa-sigma clipped mean (default) */
  LUMEN_STACK_MEDIAN = 1,
  LUMEN_STACK_MEAN = 2,
  LUMEN_STACK_MAXIMUM = 3     /* star trails */
} LumenStackMode;

typedef struct {
  int32_t align_stars;        /* 1 = register on stars, 0 = fixed tripod */
  int32_t stack_mode;         /* LumenStackMode */
  float kappa;                /* sigma clip threshold (2.5) */
  int32_t remove_hot_pixels;  /* 1 = cosmetic correction */
  int32_t remove_gradient;    /* 1 = subtract polynomial sky background */
  const char* temp_dir;       /* writable dir for frame cache (clip/median) */
} LumenAstroParams;

typedef struct {
  int32_t stars_detected;
  int32_t stars_matched;
  float rotation_deg;
  float shift_x, shift_y;     /* full-res pixels */
  float rms_error_px;
  int32_t accepted;           /* 0 = frame rejected (no registration) */
} LumenAstroFrameStats;

void lumen_astro_params_default(LumenAstroParams* p);
LumenAstro* lumen_astro_create(const LumenRawInfo* info,
                               const LumenAstroParams* params);
int32_t lumen_astro_add_dark(LumenAstro* a, const uint16_t* data);
int32_t lumen_astro_add_light(LumenAstro* a, const uint16_t* data,
                              LumenAstroFrameStats* stats);
int32_t lumen_astro_frame_count(LumenAstro* a);
int32_t lumen_astro_finalize(LumenAstro* a);
/* Renders with an automatic astro stretch (fp may be NULL). */
int32_t lumen_astro_render(LumenAstro* a, const LumenColorInfo* color,
                           const LumenFinishParams* fp, uint8_t* out_rgba,
                           int32_t out_stride);
/* Quick half-size preview of the running stack (works before finalize). */
int32_t lumen_astro_preview(LumenAstro* a, const LumenColorInfo* color,
                            uint8_t* out_rgba, int32_t out_stride);
int32_t lumen_astro_write_dng(LumenAstro* a, const LumenColorInfo* color,
                              const LumenDngMeta* meta, const char* path);
void lumen_astro_destroy(LumenAstro* a);

/* ---- color helpers ---------------------------------------------------- */
/* Fills wb_gains and cam_to_srgb from the DNG matrices + as_shot_neutral. */
int32_t lumen_color_resolve(LumenColorInfo* c);
/* WB gains for a target white (Kelvin + tint, tint in +-150 like DNG). */
int32_t lumen_wb_gains_for_cct(const LumenColorInfo* c, float kelvin,
                               float tint, float out_gains[3]);
/* Estimated correlated color temperature of the as-shot neutral. */
float lumen_estimate_cct(const LumenColorInfo* c);

/* ---- DNG reading (metadata + uncompressed CFA data) ------------------ */
typedef struct {
  LumenRawInfo raw;
  LumenColorInfo color;
  float exposure_time_s;
  float f_number;
  float focal_length_mm;
  int32_t iso;
  int32_t orientation;
  int32_t has_noise_profile;
  int32_t compressed;          /* 1 = pixel data compressed (not decodable) */
  char make[64];
  char model[64];
} LumenDngInfo;

int32_t lumen_dng_read_info(const uint8_t* bytes, size_t len, LumenDngInfo* out);
/* Decodes uncompressed 8/10/12/14/16-bit CFA data into out (width*height). */
int32_t lumen_dng_read_pixels(const uint8_t* bytes, size_t len, uint16_t* out);

/* ---- misc -------------------------------------------------------------- */
/* Lumen Log curve used for Log video (Android TONEMAP_CURVE). */
float lumen_log_encode(float linear);
float lumen_log_decode(float encoded);
/* Writes a 33^3 .cube LUT mapping Lumen Log (Rec.709 primaries) to
 * Rec.709 display. */
int32_t lumen_write_log_lut(const char* path, int32_t size);

/* Estimates the noise model from one frame (fills noise_scale/offset). */
int32_t lumen_estimate_noise(LumenRawInfo* info, const uint16_t* data);

/* Image sharpness score (variance of Laplacian), for "lucky" selection. */
float lumen_sharpness(const LumenRawInfo* info, const uint16_t* data);

const char* lumen_core_version(void);

#ifdef __cplusplus
}
#endif
#endif /* LUMEN_CORE_H */
