#!/bin/zsh
# Gera os assets de produção do mascote a partir do vídeo fonte. Reproduzível: mesma fonte + mesmo script = mesmos arquivos.
#   uso: mvp-binance-panel/tools/mascot/build_mascot_assets.sh <source.mp4> [outDir]
#   padrão de outDir: mvp-binance-panel/src/main/resources/panel/mascot
# Parâmetros (em Build.java, tabela STATES): intervalos de frames de origem, loop (loopStart/loopEnd + crossfade), fps de produção, poster, hold.
# Canvas lógico: recorte quadrado de 960 px do vídeo (1024), centro óptico por estado, reduzido a 384 px (nunca ampliado na UI). Sem dependências além de JDK 21, ffmpeg e Jackson (do repositório Maven local).
# Prioridade reduzida (nice 10, 2 threads) para não competir com a captura científica.
set -euo pipefail
HERE="${0:A:h}"; ROOT="${HERE:h:h}"
SRC="${1:?uso: build_mascot_assets.sh <source.mp4> [outDir]}"
OUT="${2:-$ROOT/src/main/resources/panel/mascot}"
export JAVA_HOME="${JAVA_HOME:-$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home}"
J="$JAVA_HOME/bin/java"
[[ -f "$SRC" ]] || { echo "fonte não encontrada: $SRC"; exit 2; }
SHA=$(shasum -a 256 "$SRC" | cut -d' ' -f1)
echo "== fonte: $SRC sha256=$SHA"
# 1. auditoria da fonte
probe() { ffprobe -v error -select_streams "$1" -show_entries "stream=$2" -of default=nw=1:nk=1 "$SRC"; }
[[ "$(probe v:0 codec_name)" == h264 && "$(probe v:0 width)" == 1024 && "$(probe v:0 height)" == 1024 && "$(probe v:0 r_frame_rate)" == 30/1 && "$(probe v:0 nb_frames)" == 1368 ]] || { echo "BLOCKED: a fonte não é o vídeo esperado (h264 1024x1024 30fps 1368 frames)"; exit 3; }
[[ -z "$(probe a:0 codec_name)" ]] || { echo "BLOCKED: a fonte tem áudio"; exit 3; }
echo "OK: h264 1024x1024 30 fps, 1368 frames, sem áudio"
# 2. build
rm -rf "$OUT/states" "$OUT/posters" "$OUT/mascot-manifest.json"
nice -n 10 "$J" -Xmx3g "$HERE/Build.java" "$SRC" "$OUT" "$SHA"
# 3. verificações
CP=$(find "$HOME/.m2/repository/com/fasterxml/jackson/core" -name "*2.20*.jar" ! -name "*sources*" | tr '\n' ':')
nice -n 10 "$J" -cp "$CP" "$HERE/Verify.java" "$OUT"
