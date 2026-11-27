#!/usr/bin/env python3
"""
Export the open-source AI models used by Lumen Camera to ONNX.

Models (official weights, unmodified):
  * FFDNet colour denoiser  — Kai Zhang et al., KAIR toolbox (MIT)
      https://github.com/cszn/KAIR/releases/download/v1.0/ffdnet_color.pth
  * Real-ESRGAN general x4v3 (SRVGGNetCompact) — Xintao Wang et al. (BSD-3-Clause)
      https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth

Usage (PC or Google Colab):
    pip install torch onnx onnxruntime numpy
    python tools/export_models.py --out models
    # then copy models/*.onnx into
    #   android/app/src/main/assets/models/   and   ios/LumenCamera/Resources/Models/

--random-weights exports the same graphs with random weights (used in CI to
verify the export + the tiling contract without downloading anything).

I/O contract used by both apps (NCHW float32, RGB in [0,1]). The graphs have
FIXED tile shapes and contain only Conv / ConvTranspose / PRelu / Relu / Mul /
Concat / Add, so the whole network runs on the Core ML (Neural Engine / GPU)
and NNAPI execution providers instead of falling back to the CPU:
  lumen_denoise.onnx : inputs  image[1,3,256,256], sigma[1,1,1,1] (noise std, 0..0.3)
                       output  denoised[1,3,256,256]          tile 256, overlap 32
  lumen_sr_x4.onnx   : input   image[1,3,192,192]
                       output  upscaled[1,3,768,768]          tile 192, overlap 40
Pixel (un)shuffles are expressed as one-hot stride-r Conv / ConvTranspose
layers, which is numerically exact. --dynamic exports variable-size graphs
instead (desktop use).
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.request

import numpy as np

try:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
except ImportError:
    sys.exit("PyTorch is required: pip install torch")

URLS = {
    "ffdnet": "https://github.com/cszn/KAIR/releases/download/v1.0/ffdnet_color.pth",
    "srvgg": "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth",
}


# --------------------------------------------------------------------------
# Architectures (re-implemented to match the official state_dict keys exactly)
def pixel_unshuffle(x, r):  # KAIR basicblock.pixel_unshuffle
    b, c, h, w = x.shape
    x = x.contiguous().view(b, c, h // r, r, w // r, r)
    return x.permute(0, 1, 3, 5, 2, 4).contiguous().view(b, c * r * r, h // r, w // r)


class FFDNet(nn.Module):
    """KAIR models/network_ffdnet.py, colour model: in_nc=3, nc=96, nb=12."""

    def __init__(self, in_nc=3, out_nc=3, nc=96, nb=12):
        super().__init__()
        sf = 2
        layers = [nn.Conv2d(in_nc * sf * sf + 1, nc, 3, 1, 1, bias=True), nn.ReLU(inplace=True)]
        for _ in range(nb - 2):
            layers += [nn.Conv2d(nc, nc, 3, 1, 1, bias=True), nn.ReLU(inplace=True)]
        layers += [nn.Conv2d(nc, out_nc * sf * sf, 3, 1, 1, bias=True)]
        self.model = nn.Sequential(*layers)  # keys model.0, model.2, ... model.22
        self.m_up = nn.PixelShuffle(sf)

    def forward(self, x, sigma):
        # The apps always feed even sizes, so the original replication padding
        # is unnecessary and left out of the exported graph.
        x = pixel_unshuffle(x, 2)
        m = sigma.expand(x.shape[0], 1, x.shape[2], x.shape[3])
        x = torch.cat((x, m), 1)
        x = self.model(x)
        return self.m_up(x)


class SRVGGNetCompact(nn.Module):
    """Real-ESRGAN realesrgan/archs/srvgg_arch.py (general-x4v3: 64 feat, 32 conv, PReLU)."""

    def __init__(self, num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=32, upscale=4):
        super().__init__()
        self.upscale = upscale
        self.body = nn.ModuleList()
        self.body.append(nn.Conv2d(num_in_ch, num_feat, 3, 1, 1))
        self.body.append(nn.PReLU(num_parameters=num_feat))
        for _ in range(num_conv):
            self.body.append(nn.Conv2d(num_feat, num_feat, 3, 1, 1))
            self.body.append(nn.PReLU(num_parameters=num_feat))
        self.body.append(nn.Conv2d(num_feat, num_out_ch * upscale * upscale, 3, 1, 1))
        self.upsampler = nn.PixelShuffle(upscale)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        out = self.upsampler(out)
        return out + F.interpolate(x, scale_factor=self.upscale, mode="nearest")


# --------------------------------------------------------------------------
# Export wrappers: same trained layers, accelerator-friendly reshuffles.
def one_hot_space_to_depth(c, r):
    """Conv c -> c*r*r, kernel r, stride r; channel order c*r*r + i*r + j (= pixel_unshuffle)."""
    conv = nn.Conv2d(c, c * r * r, r, stride=r, bias=False)
    w = torch.zeros(c * r * r, c, r, r)
    for ch in range(c):
        for i in range(r):
            for j in range(r):
                w[ch * r * r + i * r + j, ch, i, j] = 1.0
    conv.weight.data.copy_(w)
    conv.weight.requires_grad_(False)
    return conv


def one_hot_depth_to_space(c, r):
    """ConvTranspose c*r*r -> c, kernel r, stride r (= PixelShuffle(r))."""
    deconv = nn.ConvTranspose2d(c * r * r, c, r, stride=r, bias=False)
    w = torch.zeros(c * r * r, c, r, r)
    for ch in range(c):
        for i in range(r):
            for j in range(r):
                w[ch * r * r + i * r + j, ch, i, j] = 1.0
    deconv.weight.data.copy_(w)
    deconv.weight.requires_grad_(False)
    return deconv


def nearest_upsample(c, r):
    """ConvTranspose c -> c, kernel r, stride r with ones on the diagonal (= nearest x r)."""
    deconv = nn.ConvTranspose2d(c, c, r, stride=r, bias=False)
    w = torch.zeros(c, c, r, r)
    for ch in range(c):
        w[ch, ch] = 1.0
    deconv.weight.data.copy_(w)
    deconv.weight.requires_grad_(False)
    return deconv


class FFDNetExport(nn.Module):
    def __init__(self, net, h, w):
        super().__init__()
        self.s2d = one_hot_space_to_depth(3, 2)
        self.body = net.model
        self.d2s = one_hot_depth_to_space(3, 2)
        self.register_buffer("ones", torch.ones(1, 1, h // 2, w // 2))

    def forward(self, x, sigma):
        y = torch.cat((self.s2d(x), self.ones * sigma), 1)
        return self.d2s(self.body(y))


class SRExport(nn.Module):
    def __init__(self, net):
        super().__init__()
        self.body = net.body
        self.d2s = one_hot_depth_to_space(3, net.upscale)
        self.up = nearest_upsample(3, net.upscale)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        return self.d2s(out) + self.up(x)


# --------------------------------------------------------------------------
def download(url, dst):
    if os.path.exists(dst):
        return dst
    print(f"downloading {url}")
    tmp = dst + ".part"
    urllib.request.urlretrieve(url, tmp)
    os.replace(tmp, dst)
    return dst


def load_weights(model, path, key=None):
    sd = torch.load(path, map_location="cpu", weights_only=True)
    if isinstance(sd, dict):
        for k in ("params_ema", "params", "state_dict"):
            if key is None and k in sd and isinstance(sd[k], dict):
                sd = sd[k]
                break
    model.load_state_dict(sd, strict=True)  # fails loudly if keys differ
    return model


def export(model, args_example, path, input_names, output_names, dynamic_axes):
    model.eval()
    kwargs = dict(input_names=input_names, output_names=output_names, opset_version=17, do_constant_folding=True)
    if dynamic_axes:
        kwargs["dynamic_axes"] = dynamic_axes
    try:
        torch.onnx.export(model, args_example, path, dynamo=False, **kwargs)
    except TypeError:  # older torch without the dynamo flag
        torch.onnx.export(model, args_example, path, **kwargs)
    import onnx
    m = onnx.load(path)
    onnx.checker.check_model(m)
    return path


def op_types(path):
    import onnx
    return sorted({n.op_type for n in onnx.load(path).graph.node})


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# --------------------------------------------------------------------------
# Reference tiler — Kotlin (AiEngine.kt) and Swift (AiEngine.swift) follow this
# exactly. The image is cut into step x step "cores" (step = tile - 2*overlap).
# Every model call gets exactly tile x tile pixels: the core plus `overlap`
# pixels of context on every side, with coordinates outside the image clamped
# to the nearest edge pixel. overlap >= receptive-field radius, so each output
# pixel equals full-image inference on the edge-extended image.
def tiled_run(run, img, scale, tile, overlap):
    """img: float32 [3,H,W] (H,W even). run(x[1,3,tile,tile]) -> [1,3,tile*scale,tile*scale]."""
    _, H, W = img.shape
    step = tile - 2 * overlap
    out = np.zeros((3, H * scale, W * scale), np.float32)
    for cy0 in range(0, H, step):
        cy1 = min(H, cy0 + step)
        ys = np.clip(np.arange(cy0 - overlap, cy0 - overlap + tile), 0, H - 1)
        for cx0 in range(0, W, step):
            cx1 = min(W, cx0 + step)
            xs = np.clip(np.arange(cx0 - overlap, cx0 - overlap + tile), 0, W - 1)
            res = run(img[None][:, :, ys][:, :, :, xs])[0]
            o = overlap * scale
            out[:, cy0 * scale:cy1 * scale, cx0 * scale:cx1 * scale] = \
                res[:, o:o + (cy1 - cy0) * scale, o:o + (cx1 - cx0) * scale]
    return out


def edge_reference(model_fn, img, scale, pad):
    """Full-image inference on the edge-extended image, cropped back."""
    p = np.pad(img, ((0, 0), (pad, pad), (pad, pad)), mode="edge")
    with torch.no_grad():
        full = model_fn(torch.from_numpy(p[None])).numpy()[0]
    o = pad * scale
    return full[:, o:o + img.shape[1] * scale, o:o + img.shape[2] * scale]


def psnr(a, b):
    return 10 * np.log10(1.0 / max(1e-12, float(np.mean((a - b) ** 2))))


def synthetic_image(h, w, seed=0):
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    img = np.stack([0.5 + 0.3 * np.sin(x / 9.0), 0.4 + 0.3 * np.cos(y / 13.0), 0.5 + 0.2 * np.sin((x + y) / 21.0)])
    blocks = rng.random((3, h // 16 + 1, w // 16 + 1)).astype(np.float32)
    img = 0.7 * img + 0.3 * np.kron(blocks, np.ones((16, 16), np.float32))[:, :h, :w]
    return np.clip(img, 0, 1).astype(np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="models")
    ap.add_argument("--cache", default=os.path.join(os.path.expanduser("~"), ".cache", "lumen"))
    ap.add_argument("--random-weights", action="store_true", help="skip downloads (CI / offline test)")
    ap.add_argument("--dynamic", action="store_true", help="variable-size graphs (desktop); the apps need the default fixed tiles")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    os.makedirs(args.cache, exist_ok=True)
    torch.manual_seed(0)

    import onnxruntime as ort

    manifest = {"version": 1, "models": []}

    # ---- denoiser -------------------------------------------------------
    DT, DO = 256, 32
    den = FFDNet()
    if not args.random_weights:
        load_weights(den, download(URLS["ffdnet"], os.path.join(args.cache, "ffdnet_color.pth")))
    den.eval()
    p_den = os.path.join(args.out, "lumen_denoise.onnx")
    if args.dynamic:
        export(den, (torch.rand(1, 3, 64, 64), torch.full((1, 1, 1, 1), 0.05)), p_den, ["image", "sigma"],
               ["denoised"], {"image": {2: "h", 3: "w"}, "denoised": {2: "h", 3: "w"}})
    else:
        export(FFDNetExport(den, DT, DT).eval(), (torch.rand(1, 3, DT, DT), torch.full((1, 1, 1, 1), 0.05)),
               p_den, ["image", "sigma"], ["denoised"], None)
    print("denoise ops:", op_types(p_den))
    sess = ort.InferenceSession(p_den, providers=["CPUExecutionProvider"])
    x = torch.rand(1, 3, DT, DT)
    s = torch.full((1, 1, 1, 1), 0.1)
    with torch.no_grad():
        ref = den(x, s).numpy()
    got = sess.run(None, {"image": x.numpy(), "sigma": s.numpy()})[0]
    diff = float(np.abs(ref - got).max())
    print(f"denoise: torch vs onnxruntime max |diff| = {diff:.2e}")
    assert diff < 1e-3, "ONNX parity failed for denoiser"

    # tiling contract: tiled == full-image inference on the edge-extended image
    img = synthetic_image(300, 420)  # even sizes, not multiples of the tile step
    noisy = np.clip(img + np.random.default_rng(1).normal(0, 0.08, img.shape).astype(np.float32), 0, 1)
    sig = np.full((1, 1, 1, 1), 0.08, np.float32)
    run_den = lambda t: sess.run(None, {"image": np.ascontiguousarray(t, np.float32), "sigma": sig})[0]
    tiled = tiled_run(run_den, noisy, 1, DT, DO)
    full = edge_reference(lambda t: den(t, torch.from_numpy(sig)), noisy, 1, DO)
    tdiff = float(np.abs(full - tiled).max())
    print(f"denoise: tiled vs full max |diff| = {tdiff:.2e}")
    assert tdiff < 1e-3, "tiling contract broken for denoiser"
    if not args.random_weights:
        print(f"denoise sanity: PSNR noisy {psnr(noisy, img):.2f} dB -> denoised {psnr(np.clip(tiled,0,1), img):.2f} dB")
    manifest["models"].append({
        "file": "lumen_denoise.onnx", "task": "denoise", "arch": "FFDNet (colour)", "license": "MIT",
        "source": URLS["ffdnet"], "tile": None if args.dynamic else DT, "overlap": DO,
        "inputs": {"image": f"float32[1,3,{DT},{DT}] RGB 0..1" if not args.dynamic else "float32[1,3,H,W] RGB 0..1, H,W even",
                   "sigma": "float32[1,1,1,1] noise std 0..0.3"},
        "outputs": {"denoised": "float32[1,3,H,W]"}, "sha256": sha256(p_den),
        "random_weights": bool(args.random_weights)})

    # ---- super-resolution ------------------------------------------------
    ST, SO = 192, 40
    sr = SRVGGNetCompact()
    if not args.random_weights:
        load_weights(sr, download(URLS["srvgg"], os.path.join(args.cache, "realesr-general-x4v3.pth")))
    sr.eval()
    p_sr = os.path.join(args.out, "lumen_sr_x4.onnx")
    if args.dynamic:
        export(sr, (torch.rand(1, 3, 32, 32),), p_sr, ["image"], ["upscaled"],
               {"image": {2: "h", 3: "w"}, "upscaled": {2: "h4", 3: "w4"}})
    else:
        export(SRExport(sr).eval(), (torch.rand(1, 3, ST, ST),), p_sr, ["image"], ["upscaled"], None)
    print("sr_x4 ops:", op_types(p_sr))
    sess2 = ort.InferenceSession(p_sr, providers=["CPUExecutionProvider"])
    x = torch.rand(1, 3, ST, ST)
    with torch.no_grad():
        ref = sr(x).numpy()
    got = sess2.run(None, {"image": x.numpy()})[0]
    diff = float(np.abs(ref - got).max())
    print(f"sr_x4: torch vs onnxruntime max |diff| = {diff:.2e}, output {got.shape}")
    assert got.shape == (1, 3, ST * 4, ST * 4) and diff < 1e-3, "ONNX parity failed for SR"
    run_sr = lambda t: sess2.run(None, {"image": np.ascontiguousarray(t, np.float32)})[0]
    small = synthetic_image(130, 250, 3)
    tiled = tiled_run(run_sr, small, 4, ST, SO)
    full = edge_reference(sr, small, 4, SO)
    tdiff = float(np.abs(full - tiled).max())
    print(f"sr_x4: tiled vs full max |diff| = {tdiff:.2e}")
    assert tdiff < 1e-3, "tiling contract broken for SR"
    manifest["models"].append({
        "file": "lumen_sr_x4.onnx", "task": "super_resolution_x4", "arch": "SRVGGNetCompact (Real-ESRGAN general-x4v3)",
        "license": "BSD-3-Clause", "source": URLS["srvgg"], "tile": None if args.dynamic else ST, "overlap": SO,
        "inputs": {"image": f"float32[1,3,{ST},{ST}] RGB 0..1" if not args.dynamic else "float32[1,3,H,W] RGB 0..1"},
        "outputs": {"upscaled": "float32[1,3,4H,4W]"}, "sha256": sha256(p_sr),
        "random_weights": bool(args.random_weights)})

    with open(os.path.join(args.out, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    for m in manifest["models"]:
        sz = os.path.getsize(os.path.join(args.out, m["file"])) / 1e6
        print(f"wrote {m['file']} ({sz:.1f} MB)")
    if args.random_weights:
        print("NOTE: random weights — run without --random-weights to get the real models.")


if __name__ == "__main__":
    main()
