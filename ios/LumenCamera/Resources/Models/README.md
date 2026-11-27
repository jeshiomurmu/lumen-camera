Generated ONNX models go here (lumen_denoise.onnx, lumen_sr_x4.onnx):

    python tools/export_models.py --out models
    cp models/*.onnx ios/LumenCamera/Resources/Models/

Re-run `xcodegen` after adding them so they are bundled. Without them, AI switches do nothing.
