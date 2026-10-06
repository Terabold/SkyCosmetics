#!/usr/bin/env bash
# Prints the gametest screenshots named in .github/show-screenshots.txt as base64 JPEGs between markers, so
# a coding session that cannot download workflow artifacts can still look at them through the job log.
# Each line: a name pattern (grep -E) and optionally a width in pixels (default 960).
set -u
dir=build/run/clientGameTest/screenshots
command -v convert >/dev/null || sudo apt-get install -y -q imagemagick >/dev/null
ls "$dir" 2>/dev/null | sed 's/^/screenshot: /'
while read -r pattern width || [ -n "${pattern:-}" ]; do
  case "${pattern:-}" in ''|'#'*) continue ;; esac
  for f in $(ls "$dir" | grep -E "$pattern"); do
    echo "=== image $f"
    convert "$dir/$f" -resize "${width:-960}x" -quality 80 jpg:- | base64 -w 200
    echo "=== end $f"
  done
done < .github/show-screenshots.txt
