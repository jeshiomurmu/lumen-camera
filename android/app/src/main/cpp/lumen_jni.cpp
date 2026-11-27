// JNI bridge: app.lumen.camera.core.NativeCore <-> Lumen imaging core (C API).
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "lumen_core.h"

#define LOG_TAG "LumenJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kColorSize = 54;
constexpr int kFinishSize = 11;

void fill_raw_info(JNIEnv* env, LumenRawInfo& i, jint w, jint h, jint stride, jint cfa, jboolean quad,
                   jfloatArray black, jfloat white, jfloatArray ns, jfloatArray no) {
  std::memset(&i, 0, sizeof(i));
  i.width = w;
  i.height = h;
  i.row_stride = stride;
  i.cfa = cfa;
  i.quad_bayer = quad ? 1 : 0;
  i.white_level = white;
  if (black && env->GetArrayLength(black) >= 4) env->GetFloatArrayRegion(black, 0, 4, i.black_level);
  if (ns && env->GetArrayLength(ns) >= 4) env->GetFloatArrayRegion(ns, 0, 4, i.noise_scale);
  if (no && env->GetArrayLength(no) >= 4) env->GetFloatArrayRegion(no, 0, 4, i.noise_offset);
}

bool unpack_color(JNIEnv* env, jfloatArray arr, LumenColorInfo& c) {
  std::memset(&c, 0, sizeof(c));
  if (!arr || env->GetArrayLength(arr) < kColorSize) return false;
  float v[kColorSize];
  env->GetFloatArrayRegion(arr, 0, kColorSize, v);
  std::memcpy(c.wb_gains, v + 0, sizeof(float) * 3);
  std::memcpy(c.cam_to_srgb, v + 3, sizeof(float) * 9);
  std::memcpy(c.color_matrix1, v + 12, sizeof(float) * 9);
  std::memcpy(c.color_matrix2, v + 21, sizeof(float) * 9);
  std::memcpy(c.forward_matrix1, v + 30, sizeof(float) * 9);
  std::memcpy(c.forward_matrix2, v + 39, sizeof(float) * 9);
  c.illuminant1 = int32_t(v[48]);
  c.illuminant2 = int32_t(v[49]);
  std::memcpy(c.as_shot_neutral, v + 50, sizeof(float) * 3);
  c.baseline_exposure = v[53];
  return true;
}

jfloatArray pack_color(JNIEnv* env, const LumenColorInfo& c) {
  float v[kColorSize];
  std::memcpy(v + 0, c.wb_gains, sizeof(float) * 3);
  std::memcpy(v + 3, c.cam_to_srgb, sizeof(float) * 9);
  std::memcpy(v + 12, c.color_matrix1, sizeof(float) * 9);
  std::memcpy(v + 21, c.color_matrix2, sizeof(float) * 9);
  std::memcpy(v + 30, c.forward_matrix1, sizeof(float) * 9);
  std::memcpy(v + 39, c.forward_matrix2, sizeof(float) * 9);
  v[48] = float(c.illuminant1);
  v[49] = float(c.illuminant2);
  std::memcpy(v + 50, c.as_shot_neutral, sizeof(float) * 3);
  v[53] = c.baseline_exposure;
  jfloatArray out = env->NewFloatArray(kColorSize);
  env->SetFloatArrayRegion(out, 0, kColorSize, v);
  return out;
}

bool unpack_finish(JNIEnv* env, jfloatArray arr, LumenFinishParams& p) {
  if (!arr || env->GetArrayLength(arr) < kFinishSize) return false;
  float v[kFinishSize];
  env->GetFloatArrayRegion(arr, 0, kFinishSize, v);
  p.profile = int32_t(v[0]);
  p.exposure_ev = v[1];
  p.auto_exposure = int32_t(v[2]);
  p.shadows = v[3];
  p.contrast = v[4];
  p.saturation = v[5];
  p.sharpen = v[6];
  p.chroma_nr = v[7];
  p.warmth = v[8];
  p.output_p3 = int32_t(v[9]);
  p.half_size = int32_t(v[10]);
  return true;
}

struct MetaHolder {
  std::string s[5];
  LumenDngMeta meta{};
};

void unpack_meta(JNIEnv* env, jobjectArray strs, jfloatArray nums, MetaHolder& h) {
  const char** fields[5] = {&h.meta.make, &h.meta.model, &h.meta.unique_model, &h.meta.software, &h.meta.datetime};
  for (int i = 0; i < 5; ++i) {
    *fields[i] = nullptr;
    if (!strs || i >= env->GetArrayLength(strs)) continue;
    auto js = static_cast<jstring>(env->GetObjectArrayElement(strs, i));
    if (!js) continue;
    const char* c = env->GetStringUTFChars(js, nullptr);
    h.s[i] = c ? c : "";
    env->ReleaseStringUTFChars(js, c);
    env->DeleteLocalRef(js);
    *fields[i] = h.s[i].c_str();
  }
  float v[5] = {1, 0, 0, 0, 0};
  if (nums && env->GetArrayLength(nums) >= 5) env->GetFloatArrayRegion(nums, 0, 5, v);
  h.meta.orientation = int32_t(v[0]);
  h.meta.exposure_time_s = v[1];
  h.meta.f_number = v[2];
  h.meta.focal_length_mm = v[3];
  h.meta.iso = int32_t(v[4]);
}

struct LockedBitmap {
  JNIEnv* env;
  jobject bmp;
  void* px = nullptr;
  AndroidBitmapInfo info{};
  bool ok = false;
  LockedBitmap(JNIEnv* e, jobject b) : env(e), bmp(b) {
    if (AndroidBitmap_getInfo(env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    ok = AndroidBitmap_lockPixels(env, bmp, &px) == ANDROID_BITMAP_RESULT_SUCCESS;
  }
  ~LockedBitmap() {
    if (ok) AndroidBitmap_unlockPixels(env, bmp);
  }
};

std::string jstr(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r = c ? c : "";
  env->ReleaseStringUTFChars(s, c);
  return r;
}

}  // namespace

#define FN(name) Java_app_lumen_camera_core_NativeCore_##name

extern "C" {

JNIEXPORT jstring JNICALL FN(version)(JNIEnv* env, jobject) { return env->NewStringUTF(lumen_core_version()); }

// ---- burst ----------------------------------------------------------------
JNIEXPORT jlong JNICALL FN(burstCreate)(JNIEnv* env, jobject, jint w, jint h, jint stride, jint cfa,
                                        jboolean quad, jfloatArray black, jfloat white, jfloatArray ns,
                                        jfloatArray no, jfloat robustness, jfloat spatial, jint refMode) {
  LumenRawInfo info;
  fill_raw_info(env, info, w, h, stride, cfa, quad, black, white, ns, no);
  LumenMergeParams mp;
  lumen_merge_params_default(&mp);
  if (robustness > 0) mp.robustness = robustness;
  mp.spatial_denoise = spatial;
  mp.reference_mode = refMode;
  return reinterpret_cast<jlong>(lumen_burst_create(&info, &mp));
}

JNIEXPORT jint JNICALL FN(burstAddFrame)(JNIEnv* env, jobject, jlong h, jobject buf) {
  auto* b = reinterpret_cast<LumenBurst*>(h);
  auto* p = static_cast<const uint16_t*>(env->GetDirectBufferAddress(buf));
  if (!b || !p) return LUMEN_ERR_INVALID_ARG;
  return lumen_burst_add_frame(b, p);
}

JNIEXPORT jint JNICALL FN(burstAddFrameRaw10)(JNIEnv* env, jobject, jlong h, jobject buf, jint stride) {
  auto* b = reinterpret_cast<LumenBurst*>(h);
  auto* p = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buf));
  if (!b || !p) return LUMEN_ERR_INVALID_ARG;
  return lumen_burst_add_frame_raw10(b, p, stride);
}

JNIEXPORT jfloatArray JNICALL FN(burstFinalize)(JNIEnv* env, jobject, jlong h) {
  auto* b = reinterpret_cast<LumenBurst*>(h);
  LumenBurstStats st{};
  if (!b || lumen_burst_finalize(b, &st) != LUMEN_OK) return nullptr;
  float v[5] = {float(st.frames_merged), float(st.reference_index), st.mean_motion_px, st.noise_scale,
                st.noise_offset};
  jfloatArray out = env->NewFloatArray(5);
  env->SetFloatArrayRegion(out, 0, 5, v);
  return out;
}

JNIEXPORT jint JNICALL FN(burstRender)(JNIEnv* env, jobject, jlong h, jfloatArray color, jfloatArray finish,
                                       jobject bitmap) {
  auto* b = reinterpret_cast<LumenBurst*>(h);
  LumenColorInfo c;
  LumenFinishParams fp;
  lumen_finish_params_for_profile(LUMEN_PROFILE_NATURAL, &fp);
  if (!b || !unpack_color(env, color, c)) return LUMEN_ERR_INVALID_ARG;
  unpack_finish(env, finish, fp);
  LockedBitmap lb(env, bitmap);
  if (!lb.ok) return LUMEN_ERR_INVALID_ARG;
  return lumen_burst_render(b, &c, &fp, static_cast<uint8_t*>(lb.px), int32_t(lb.info.stride));
}

JNIEXPORT jfloat JNICALL FN(burstDisplaySigma)(JNIEnv*, jobject, jlong h) {
  return lumen_burst_display_sigma(reinterpret_cast<LumenBurst*>(h));
}

JNIEXPORT jint JNICALL FN(burstWriteDng)(JNIEnv* env, jobject, jlong h, jfloatArray color, jobjectArray ms,
                                         jfloatArray mn, jstring path) {
  auto* b = reinterpret_cast<LumenBurst*>(h);
  LumenColorInfo c;
  if (!b || !unpack_color(env, color, c)) return LUMEN_ERR_INVALID_ARG;
  MetaHolder mh;
  unpack_meta(env, ms, mn, mh);
  std::string p = jstr(env, path);
  return lumen_burst_write_dng(b, &c, &mh.meta, p.c_str());
}

JNIEXPORT void JNICALL FN(burstDestroy)(JNIEnv*, jobject, jlong h) {
  lumen_burst_destroy(reinterpret_cast<LumenBurst*>(h));
}

// ---- astro ----------------------------------------------------------------
JNIEXPORT jlong JNICALL FN(astroCreate)(JNIEnv* env, jobject, jint w, jint h, jint stride, jint cfa,
                                        jboolean quad, jfloatArray black, jfloat white, jfloatArray ns,
                                        jfloatArray no, jboolean align, jint mode, jfloat kappa,
                                        jboolean hot, jboolean gradient, jstring tempDir) {
  LumenRawInfo info;
  fill_raw_info(env, info, w, h, stride, cfa, quad, black, white, ns, no);
  static thread_local std::string dir;
  dir = jstr(env, tempDir);
  LumenAstroParams ap;
  lumen_astro_params_default(&ap);
  ap.align_stars = align ? 1 : 0;
  ap.stack_mode = mode;
  if (kappa > 0) ap.kappa = kappa;
  ap.remove_hot_pixels = hot ? 1 : 0;
  ap.remove_gradient = gradient ? 1 : 0;
  ap.temp_dir = dir.empty() ? nullptr : dir.c_str();  // copied by the stacker
  return reinterpret_cast<jlong>(lumen_astro_create(&info, &ap));
}

JNIEXPORT jint JNICALL FN(astroAddDark)(JNIEnv* env, jobject, jlong h, jobject buf) {
  auto* a = reinterpret_cast<LumenAstro*>(h);
  auto* p = static_cast<const uint16_t*>(env->GetDirectBufferAddress(buf));
  if (!a || !p) return LUMEN_ERR_INVALID_ARG;
  return lumen_astro_add_dark(a, p);
}

JNIEXPORT jfloatArray JNICALL FN(astroAddLight)(JNIEnv* env, jobject, jlong h, jobject buf) {
  auto* a = reinterpret_cast<LumenAstro*>(h);
  auto* p = static_cast<const uint16_t*>(env->GetDirectBufferAddress(buf));
  LumenAstroFrameStats st{};
  int rc = (a && p) ? lumen_astro_add_light(a, p, &st) : LUMEN_ERR_INVALID_ARG;
  float v[8] = {float(rc), float(st.stars_detected), float(st.stars_matched), st.rotation_deg,
                st.shift_x, st.shift_y, st.rms_error_px, float(st.accepted)};
  jfloatArray out = env->NewFloatArray(8);
  env->SetFloatArrayRegion(out, 0, 8, v);
  return out;
}

JNIEXPORT jint JNICALL FN(astroFrameCount)(JNIEnv*, jobject, jlong h) {
  return lumen_astro_frame_count(reinterpret_cast<LumenAstro*>(h));
}

JNIEXPORT jint JNICALL FN(astroFinalize)(JNIEnv*, jobject, jlong h) {
  return lumen_astro_finalize(reinterpret_cast<LumenAstro*>(h));
}

JNIEXPORT jint JNICALL FN(astroRender)(JNIEnv* env, jobject, jlong h, jfloatArray color, jfloatArray finish,
                                       jobject bitmap) {
  auto* a = reinterpret_cast<LumenAstro*>(h);
  LumenColorInfo c;
  if (!a || !unpack_color(env, color, c)) return LUMEN_ERR_INVALID_ARG;
  LumenFinishParams fp;
  lumen_finish_params_for_profile(LUMEN_PROFILE_NATURAL, &fp);
  bool haveFp = unpack_finish(env, finish, fp);
  LockedBitmap lb(env, bitmap);
  if (!lb.ok) return LUMEN_ERR_INVALID_ARG;
  return lumen_astro_render(a, &c, haveFp ? &fp : nullptr, static_cast<uint8_t*>(lb.px), int32_t(lb.info.stride));
}

JNIEXPORT jint JNICALL FN(astroPreview)(JNIEnv* env, jobject, jlong h, jfloatArray color, jobject bitmap) {
  auto* a = reinterpret_cast<LumenAstro*>(h);
  LumenColorInfo c;
  if (!a || !unpack_color(env, color, c)) return LUMEN_ERR_INVALID_ARG;
  LockedBitmap lb(env, bitmap);
  if (!lb.ok) return LUMEN_ERR_INVALID_ARG;
  return lumen_astro_preview(a, &c, static_cast<uint8_t*>(lb.px), int32_t(lb.info.stride));
}

JNIEXPORT jint JNICALL FN(astroWriteDng)(JNIEnv* env, jobject, jlong h, jfloatArray color, jobjectArray ms,
                                         jfloatArray mn, jstring path) {
  auto* a = reinterpret_cast<LumenAstro*>(h);
  LumenColorInfo c;
  if (!a || !unpack_color(env, color, c)) return LUMEN_ERR_INVALID_ARG;
  MetaHolder mh;
  unpack_meta(env, ms, mn, mh);
  std::string p = jstr(env, path);
  return lumen_astro_write_dng(a, &c, &mh.meta, p.c_str());
}

JNIEXPORT void JNICALL FN(astroDestroy)(JNIEnv*, jobject, jlong h) {
  lumen_astro_destroy(reinterpret_cast<LumenAstro*>(h));
}

// ---- single frame -----------------------------------------------------------
JNIEXPORT jint JNICALL FN(writeRawDng)(JNIEnv* env, jobject, jint w, jint h, jint stride, jint cfa, jboolean quad,
                                       jfloatArray black, jfloat white, jfloatArray ns, jfloatArray no, jobject buf,
                                       jfloatArray color, jobjectArray ms, jfloatArray mn, jstring path) {
  LumenRawInfo info;
  fill_raw_info(env, info, w, h, stride, cfa, quad, black, white, ns, no);
  auto* p = static_cast<const uint16_t*>(env->GetDirectBufferAddress(buf));
  LumenColorInfo c;
  if (!p || !unpack_color(env, color, c)) return LUMEN_ERR_INVALID_ARG;
  MetaHolder mh;
  unpack_meta(env, ms, mn, mh);
  std::string path8 = jstr(env, path);
  return lumen_write_dng(&info, p, &c, &mh.meta, path8.c_str());
}

// ---- color / misc -------------------------------------------------------------
JNIEXPORT jfloatArray JNICALL FN(colorResolve)(JNIEnv* env, jobject, jfloatArray color) {
  LumenColorInfo c;
  if (!unpack_color(env, color, c)) return nullptr;
  lumen_color_resolve(&c);
  return pack_color(env, c);
}

JNIEXPORT jfloatArray JNICALL FN(wbGainsForCct)(JNIEnv* env, jobject, jfloatArray color, jfloat kelvin,
                                                jfloat tint) {
  LumenColorInfo c;
  if (!unpack_color(env, color, c)) return nullptr;
  float g[3];
  if (lumen_wb_gains_for_cct(&c, kelvin, tint, g) != LUMEN_OK) return nullptr;
  jfloatArray out = env->NewFloatArray(3);
  env->SetFloatArrayRegion(out, 0, 3, g);
  return out;
}

JNIEXPORT jfloat JNICALL FN(estimateCct)(JNIEnv* env, jobject, jfloatArray color) {
  LumenColorInfo c;
  if (!unpack_color(env, color, c)) return 0.f;
  return lumen_estimate_cct(&c);
}

JNIEXPORT jfloat JNICALL FN(logEncode)(JNIEnv*, jobject, jfloat x) { return lumen_log_encode(x); }

JNIEXPORT jint JNICALL FN(writeLogLut)(JNIEnv* env, jobject, jstring path) {
  std::string p = jstr(env, path);
  return lumen_write_log_lut(p.c_str(), 33);
}

}  // extern "C"

// ---- YUV burst merge (phones without RAW) --------------------------------------------
extern "C" {
JNIEXPORT jlong JNICALL FN(yuvMergeCreate)(JNIEnv*, jobject, jint w, jint h, jfloat strength) {
  return reinterpret_cast<jlong>(lumen_yuv_merge_create(w, h, strength));
}

JNIEXPORT jint JNICALL FN(yuvMergeAdd)(JNIEnv* env, jobject, jlong handle, jobject yb, jobject ub, jobject vb,
                                      jint yStride, jint uvStride, jint uvPixStride) {
  auto* m = reinterpret_cast<LumenYuvMerge*>(handle);
  auto* Y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yb));
  auto* U = static_cast<const uint8_t*>(env->GetDirectBufferAddress(ub));
  auto* V = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vb));
  if (!m || !Y || !U || !V) return LUMEN_ERR_INVALID_ARG;
  return lumen_yuv_merge_add(m, Y, yStride, U, V, uvStride, uvPixStride);
}

/** Renders into an RGBA_8888 bitmap; returns [frames, sigma, meanWeight] or null. */
JNIEXPORT jfloatArray JNICALL FN(yuvMergeRender)(JNIEnv* env, jobject, jlong handle, jobject bitmap) {
  auto* m = reinterpret_cast<LumenYuvMerge*>(handle);
  if (!m) return nullptr;
  LumenYuvMergeStats st{};
  {
    LockedBitmap lb(env, bitmap);
    if (!lb.ok) return nullptr;
    if (lumen_yuv_merge_render(m, static_cast<uint8_t*>(lb.px), int32_t(lb.info.stride), &st) != LUMEN_OK) return nullptr;
  }
  jfloatArray out = env->NewFloatArray(3);
  float v[3] = {float(st.frames), st.noise_sigma, st.mean_weight};
  env->SetFloatArrayRegion(out, 0, 3, v);
  return out;
}

JNIEXPORT void JNICALL FN(yuvMergeDestroy)(JNIEnv*, jobject, jlong handle) {
  lumen_yuv_merge_destroy(reinterpret_cast<LumenYuvMerge*>(handle));
}
}
