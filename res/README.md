Game resources are not duplicated into this directory.

User-provided assets can be loaded from `q 92 20251201y.jar.zip` for tests and
optional full-game builds. See [game resource tests](../docs/game-resource-tests.md).

Prepare test assets: `python scripts/game_resources.py`.
Build with assets: add `--resource-archive 'q 92 20251201y.jar.zip'` to
`scripts/build_cldc.py`, preferably with a separate `--output-dir` under `build/`.
Without that option, the existing resource-free build behavior is unchanged.

Stlkr's optional level12 distance profile lives in `configs/stlkr/`, not here,
so it cannot silently override another game's levels. Apply it explicitly with
`--resource-overlay configs/stlkr` together with the Stlkr `--resource-archive`.
See [level12 config optimization](../docs/level12-config-optimization.md).
