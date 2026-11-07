#!/bin/sh
# Rebuild the workspace after a sandbox reset.
#
# The sandbox has wiped the working tree repeatedly: untracked files vanish
# (test harnesses, generated resource archives, /tmp scripts) and the checkout
# rolls back to the branch base. Everything that matters is committed or on the
# remote, so this puts it back. Run from the repository root.
set -e
cd "$(dirname "$0")/.."

echo "== branch"
git fetch -q origin arena/e4c04f19-quantum
git reset -q --hard origin/arena/e4c04f19-quantum
git log --oneline -1

echo "== submodules"
git submodule update -q --init --recursive 2>/dev/null || true
# Utils is pinned to a commit its upstream no longer serves; the branch the
# port actually builds against is these three.
for p in Utils:cd339d04 UtilsFPS:937e3be UtilsText:f346800; do
  d=${p%%:*}; c=${p##*:}
  ( cd "$d" && git fetch -q origin 2>/dev/null || true; git checkout -q "$c" )
  echo "  $d -> $(cd "$d" && git rev-parse --short HEAD)"
done

echo "== utils patches"
# utils-sound.patch applies cleanly. utilstext-config.patch does NOT: it is
# written against an older UtilsText and conflicts with f346800 every time, so
# the needed hunks are re-created here instead.
( cd Utils && git apply ../tools/patches/utils-sound.patch 2>/dev/null && echo "  utils-sound OK" ) || echo "  utils-sound already applied or failed"
python3 tools/patches/apply_utilstext_config.py

echo "== toolchain"
if [ ! -x build/tools/jdk8/bin/java ]; then
  sh tools/bootstrap_toolchain.sh 2>&1 | tail -2
else
  echo "  present"
fi

echo "== res3 resource archive"
python3 tools/patches/build_res3_archive.py

echo "== done"
