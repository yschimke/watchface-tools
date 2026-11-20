# UI Builder catalog: Wear OS watch faces (WFF)

This repository is the canonical owner of the **Watch Face Format** (`wff-watchface`) target for
Compose UI Builder (ui.coo.ee, preview.coo.ee) under New design → Watch, matching the catalog
delivery layout used by `m3-catalog`, `wear-m3-catalog`, and `remote-m3-catalog`.

- `catalog.json`: delivery manifest (`system` `wff-watchface`, points at `ui-builder.json`, plus
  `uiBuilderRuntime` when published with the catalog-owned Wasm renderer).
- `ui-builder.json`: the builtins-only catalog (`wff/*` elements).
- `ui-builder/designs/`: the starting templates (analog and digital watch faces).
- `../catalog-ui-builder-renderer/`: the catalog-owned CMP/Wasm renderer runtime built against
  `compose-ui-builder`'s `:ui-builder-renderer-sdk` (opt-in via
  `-PcomposeUiBuilderDir=../compose-ui-builder`).
- `validate.py`: the pre-publish check.

`.github/workflows/ui-builder-catalog.yml` validates the catalog, builds and verifies
`:catalog-ui-builder-renderer:verifyRendererRuntime`, and publishes `catalog.json`,
`ui-builder.json`, `ui-builder/designs/`, and `ui-builder/runtime.zip` to the orphan branch
`design-artifacts/wff-watchface` of this repository.

The published branch also carries `source-commit.txt`, the commit of this repository it was built
from.
