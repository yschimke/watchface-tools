# UI Builder catalog: Wear OS watch faces (WFF)

The published catalog that puts the **Watch Face Format** target in the Compose UI Builder
(ui.coo.ee, preview.coo.ee) under New design -> Watch.

- `catalog.json`: delivery manifest (`system` `wff-watchface`, points at `ui-builder.json`).
- `ui-builder.json`: the builtins-only catalog (copied from compose-ui-builder's
  `docs/design/fixtures/ui-builder/wff-watchface-published-v1.json`, UI Builder 3.137.0). It has no
  component record: every component mirrors a WFF element.
- `ui-builder/designs/`: the two starting points (analog and digital face).
- `validate.py`: the pre-publish check.

`.github/workflows/ui-builder-catalog.yml` publishes this directory to the orphan branch
`design-artifacts/wff-watchface` of this repository, the layout compose-preview-server reads
(`catalog.json` at the root, `uiBuilderFile`, templates by their declared paths). The box is pointed
at it from preview-coo-ee's `deploy/preview.coo.ee/catalogs.json`.

When compose-ui-builder changes the WFF catalog, copy the fixture and designs here again.

The published branch also carries `source-commit.txt`, the commit of this repository it was built from.
