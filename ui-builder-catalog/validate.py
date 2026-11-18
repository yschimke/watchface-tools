#!/usr/bin/env python3
"""Cheap checks on the UI Builder catalog before it is published: JSON parses, the schema and ids
line up, and every template the catalog names is a design file that exists."""
import json, pathlib, sys

root = pathlib.Path(__file__).parent
errors = []

def load(rel):
    try:
        return json.loads((root / rel).read_text())
    except Exception as e:  # noqa: BLE001
        errors.append(f"{rel}: {e}")
        return {}

catalog = load("catalog.json")
builder = load("ui-builder.json")
if catalog.get("schema") != "design-parity-catalog/v1":
    errors.append("catalog.json: schema must be design-parity-catalog/v1")
if catalog.get("uiBuilderFile") != "ui-builder.json":
    errors.append("catalog.json: uiBuilderFile must be ui-builder.json")
if builder.get("schema") != "compose-ui-builder-catalog/v1":
    errors.append("ui-builder.json: schema must be compose-ui-builder-catalog/v1")
sem = builder.get("statusSemantics", {})
if sem.get("platform") != "watchface":
    errors.append("ui-builder.json: platform must be watchface")
if not sem.get("componentIdPrefix"):
    errors.append("ui-builder.json: componentIdPrefix is required")
if builder.get("catalog", {}).get("id") != catalog.get("system"):
    errors.append("catalog.json system must equal ui-builder.json catalog.id")
templates = sem.get("templates", [])
if not templates:
    errors.append("ui-builder.json: no templates declared")
for t in templates:
    if ".." in t.split("/") or not (root / t).is_file():
        errors.append(f"template {t} is missing or escapes the catalog")
    else:
        load(t)

for e in errors:
    print(f"error: {e}", file=sys.stderr)
sys.exit(1 if errors else 0)
