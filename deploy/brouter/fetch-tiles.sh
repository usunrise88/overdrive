#!/usr/bin/env bash
# Download BRouter routing tiles (segments4, one .rd5 file per 5x5 degree square) from brouter.de.
# The tiles are not in the repository. docs/deploy.md, section 19.
#
# Run from the repository root, so the default target is ./data/brouter/segments4 (git-ignored):
#   deploy/brouter/fetch-tiles.sh --list --region serbia
#   deploy/brouter/fetch-tiles.sh --region serbia
#   deploy/brouter/fetch-tiles.sh E10_N45
set -euo pipefail

BASE_URL="https://brouter.de/brouter/segments4"
DEFAULT_DIR="./data/brouter/segments4"

usage() {
  cat <<'EOF'
Usage: fetch-tiles.sh [--list] [--dir DIR] (--region NAME | TILE...)

  --region serbia   the four tiles that cover Serbia: E15_N40 E20_N40 E15_N45 E20_N45
  TILE...           tile names such as E10_N45 (east/west longitude, north latitude, lower-left corner)
  --list            print the tiles, their sizes (from HEAD requests) and whether they are present; download nothing
  --dir DIR         target directory (default: ./data/brouter/segments4)

Downloads are resumable: a partial download sits in NAME.rd5.part and is renamed when complete.
Tiles that already exist are skipped.
EOF
}

die() {
  echo "fetch-tiles: $*" >&2
  exit 1
}

list_only=0
target_dir="$DEFAULT_DIR"
region=""
tiles=()

if [[ $# -eq 0 ]]; then
  usage >&2
  exit 1
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --list)
      list_only=1
      shift
      ;;
    --dir)
      [[ $# -ge 2 ]] || die "--dir needs a directory"
      target_dir="$2"
      shift 2
      ;;
    --region)
      [[ $# -ge 2 ]] || die "--region needs a name"
      region="$2"
      shift 2
      ;;
    -*)
      die "unknown option: $1"
      ;;
    *)
      tiles+=("$1")
      shift
      ;;
  esac
done

if [[ -n "$region" ]]; then
  case "$region" in
    serbia) tiles+=(E15_N40 E20_N40 E15_N45 E20_N45) ;;
    *) die "unknown region: $region (known: serbia)" ;;
  esac
fi

[[ ${#tiles[@]} -gt 0 ]] || die "no tiles given (use --region serbia or tile names)"

for tile in "${tiles[@]}"; do
  [[ "$tile" =~ ^[EW][0-9]{2}_[NS][0-9]{2}$ ]] || die "not a tile name: $tile"
done

# Size in bytes from the Content-Length of a HEAD request; empty if the server does not say.
remote_size() {
  curl -sSIL --connect-timeout 15 --max-time 60 "$1" |
    tr -d '\r' |
    awk 'tolower($1) == "content-length:" { size = $2 } END { if (size != "") print size }'
}

human() {
  awk -v b="$1" 'BEGIN {
    if (b == "") { print "unknown"; exit }
    if (b >= 1048576) printf "%.0f MiB\n", b / 1048576; else printf "%.0f KiB\n", b / 1024
  }'
}

if [[ $list_only -eq 1 ]]; then
  total=0
  for tile in "${tiles[@]}"; do
    size="$(remote_size "$BASE_URL/$tile.rd5")" || die "cannot reach $BASE_URL/$tile.rd5"
    state="missing"
    [[ -f "$target_dir/$tile.rd5" ]] && state="present"
    printf '%-10s %10s  %s\n' "$tile" "$(human "$size")" "$state"
    total=$((total + ${size:-0}))
  done
  printf '%-10s %10s  (into %s)\n' "total" "$(human "$total")" "$target_dir"
  exit 0
fi

mkdir -p "$target_dir"

for tile in "${tiles[@]}"; do
  file="$target_dir/$tile.rd5"
  part="$file.part"
  url="$BASE_URL/$tile.rd5"

  if [[ -f "$file" ]]; then
    echo "skip   $tile (already present)"
    continue
  fi

  size="$(remote_size "$url" || true)"
  echo "fetch  $tile ($(human "$size"))"

  if [[ -n "$size" && -f "$part" && "$(stat -c %s "$part")" -eq "$size" ]]; then
    # An earlier run got all the bytes but was stopped before the rename.
    :
  else
    # -C - resumes a partial file. --retry covers dropped connections.
    curl -fsSL -C - --retry 5 --retry-delay 5 --connect-timeout 15 -o "$part" "$url"
  fi

  if [[ -n "$size" ]]; then
    got="$(stat -c %s "$part")"
    [[ "$got" -eq "$size" ]] || die "$tile: got $got bytes, expected $size (run again to resume)"
  fi

  mv "$part" "$file"
  echo "done   $tile"
done
