# 内置 OCR 模型（v1.9.1）

| 目录 | 模型 | 来源 | 许可 |
| --- | --- | --- | --- |
| `ppocrv6-small/` | PP-OCRv6 small（检测 + 识别 + 18708 字字典） | ModelScope `RapidAI/RapidOCR`：`onnx/PP-OCRv6/det/PP-OCRv6_det_small.onnx`、`onnx/PP-OCRv6/rec/PP-OCRv6_rec_small.onnx`、`paddle/PP-OCRv6/rec/PP-OCRv6_rec_small/ppocrv6_dict.txt` | Apache-2.0（PaddleOCR） |
| `ppocrv6-tiny/` | PP-OCRv6 tiny（检测 + 识别 + 6904 字字典） | 同上，`*_tiny.onnx`、`ppocrv6_tiny_dict.txt` | Apache-2.0（PaddleOCR） |

PP-OCRv4 和方向分类模型 `ch_ppocr_mobile_v2.0_cls_infer.onnx` 来自依赖 `rapidocr-onnx-models`，不在这里重复存放。

**为什么改过模型文件头**：程序内置的 `RapidOcr.dll` 带的是 ONNX Runtime 1.14，最高只认 IR 版本 8；原始的 PP-OCRv6 文件是 IR 10（算子集 opset 11，1.14 完全支持）。这里的文件用 `onnx` 库把 `ir_version` 字段改成 8 后重新保存，权重和计算图没有改动。见技术难点文档 3.7 节。

原始文件的 SHA-256：

```text
090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f  PP-OCRv6_det_small.onnx
6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884  PP-OCRv6_rec_small.onnx
f42c0fbd294d95eac1a550e131b277dac97462c8025fa4b6c3cec1b7894bd3d5  PP-OCRv6_det_tiny.onnx
e16e242de5937ad92609223f19bc2aff3727ee40b095f996907c24749bad251b  PP-OCRv6_rec_tiny.onnx
```
