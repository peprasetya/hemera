#!/bin/bash
# Downloads the OCR engine's models (PaddleOCR's ONNX exports, Apache-2.0, from
# huggingface.co/PaddlePaddle) into DIR, one after another and resumably; each file's size and
# sha256 are checked against what Hugging Face lists. About 250 MB.
#
# usage: download_models.sh DIR
set -u
DIR=${1:?usage: download_models.sh DIR}
HF=https://huggingface.co/PaddlePaddle
fail=0

get() { # url dest size [sha256]
  local url=$1 dest=$2 size=$3 sha=${4:-}
  mkdir -p "$(dirname "$dest")"
  if [ ! -f "$dest" ] || [ "$(stat -c %s "$dest")" != "$size" ]; then
    for try in 1 2 3 4 5 6; do
      curl -fL -C - --retry 5 --retry-delay 10 -sS -o "$dest" "$url" && break
      echo "retry $try: $dest"; sleep 15
    done
  fi
  [ "$(stat -c %s "$dest" 2>/dev/null)" = "$size" ] || { echo "SIZE MISMATCH $dest"; return 1; }
  if [ -n "$sha" ]; then echo "$sha  $dest" | sha256sum -c --quiet || { echo "SHA256 MISMATCH $dest"; return 1; }; fi
  echo "ok $dest"
}

for r in PP-OCRv6_medium_det_onnx PP-OCRv6_medium_rec_onnx \
         korean_PP-OCRv5_mobile_rec_onnx arabic_PP-OCRv5_mobile_rec_onnx th_PP-OCRv5_mobile_rec_onnx \
         devanagari_PP-OCRv5_mobile_rec_onnx cyrillic_PP-OCRv5_mobile_rec_onnx el_PP-OCRv5_mobile_rec_onnx \
         ta_PP-OCRv5_mobile_rec_onnx te_PP-OCRv5_mobile_rec_onnx \
         PP-LCNet_x1_0_doc_ori_onnx PP-LCNet_x1_0_textline_ori_onnx; do
  tree=$(curl -fsS --retry 5 "https://huggingface.co/api/models/PaddlePaddle/$r/tree/main") || { echo "NO LISTING $r"; fail=1; continue; }
  for f in inference.onnx inference.yml; do
    read -r size sha < <(printf '%s' "$tree" | python3 -c 'import json,sys; f=sys.argv[1]; e=[e for e in json.load(sys.stdin) if e["path"]==f][0]; print(e["size"], (e.get("lfs") or {}).get("oid",""))' "$f")
    get "$HF/$r/resolve/main/$f" "$DIR/$r/$f" "$size" "$sha" || fail=1
  done
done
exit $fail
