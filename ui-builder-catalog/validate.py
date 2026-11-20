#!/usr/bin/env python3
"""Cheap checks on the UI Builder catalog before it is published: JSON parses, the schema and ids
line up, every template the catalog names is a design file that exists, and if a uiBuilderRuntime
descriptor is declared its archive exists and matches its manifest."""
import json, pathlib, re, sys, zipfile

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

runtime = catalog.get("uiBuilderRuntime")
if runtime is not None:
  path = runtime.get("path", "")
  if ".." in path.split("/") or not (root / path).is_file():
    errors.append(f"uiBuilderRuntime.path {path!r} is missing or escapes the catalog")
  else:
    with zipfile.ZipFile(root / path) as zf:
      manifest = json.loads(zf.read("runtime-manifest.json").decode("utf-8"))
      if manifest.get("runtimeId") != runtime.get("runtimeId"):
        errors.append("uiBuilderRuntime.runtimeId does not match runtime-manifest.json")
      if manifest.get("protocolVersion") != runtime.get("protocolVersion"):
        errors.append("uiBuilderRuntime.protocolVersion does not match runtime-manifest.json")
      if not re.fullmatch(r"[a-f0-9]{64}", runtime.get("integritySha256", "")):
        errors.append("uiBuilderRuntime.integritySha256 must be a 64-char hex sha256")
      elif manifest.get("integritySha256") != runtime.get("integritySha256"):
        errors.append("uiBuilderRuntime.integritySha256 does not match runtime-manifest.json")

for e in errors:
  print(f"error: {e}", file=sys.stderr)
sys.exit(1 if errors else 0)
