"""Independent check of Lumen DNG files with LibRaw (rawpy) and tifffile.

    pip install rawpy tifffile numpy pillow
    python validate_dng.py /tmp/lumen_out
"""
import sys, os
import numpy as np
import rawpy
import tifffile

out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/lumen_out"
ok = True
for name in ["lumen_single.dng", "lumen_merged.dng", "lumen_astro.dng"]:
    path = os.path.join(out, name)
    with rawpy.imread(path) as r:
        raw = r.raw_image_visible
        print(f"{name}: {raw.shape} pattern={r.raw_pattern.tolist()} colors={r.color_desc.decode()} "
              f"black={r.black_level_per_channel} white={r.white_level} wb={np.round(r.camera_whitebalance,3).tolist()}")
        print("   rgb_xyz_matrix[0:3]=", np.round(r.rgb_xyz_matrix[:3], 4).tolist())
        rgb = r.postprocess(use_camera_wb=True, no_auto_bright=False, output_bps=8)
        print(f"   LibRaw developed image {rgb.shape}, mean RGB {np.round(rgb.reshape(-1,3).mean(0),1).tolist()}")
        if rgb.shape[0] < 100 or raw.max() == 0:
            ok = False
        from PIL import Image
        Image.fromarray(rgb).save(os.path.join(out, name.replace(".dng", "_libraw.png")))
    with tifffile.TiffFile(path) as t:
        tags = {tg.name: tg.value for tg in t.pages[0].tags.values()}
        need = ["DNGVersion", "CFAPattern", "BlackLevel", "WhiteLevel", "ColorMatrix1", "AsShotNeutral"]
        missing = [n for n in need if n not in tags]
        print("   tifffile tags present:", "all required" if not missing else f"MISSING {missing}")
        ok &= not missing
print("DNG validation:", "PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
