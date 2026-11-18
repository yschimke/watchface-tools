#!/usr/bin/env python3
"""Packages a repository sample face as a Watch Face Push APK and writes a catalog for it.

Used by .github/workflows/push-app-emulator.yml: the push app's fake server lists the designs in
`extra.json` (served from the runner through `adb reverse`), so a real, validated face goes
through the same list -> download -> SHA-256 -> push path as a site would serve it.

  build_push_face.py --packager <bin/wff-packager> --sample <dir> --face-name <name>
                     --out <dir> --base-url http://127.0.0.1:8765 [--marketplace pkg]

Writes <out>/face.apk, token.txt, preview.png and extra.json (a DesignList).
"""

import argparse
import hashlib
import json
import pathlib
import struct
import subprocess
import tempfile
import zlib


def png(size: int = 64) -> bytes:
    """A filled blue circle: the samples ship only XML, so this stands in for the preview."""
    rows = []
    r = size / 2
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            inside = (x - r + 0.5) ** 2 + (y - r + 0.5) ** 2 <= r * r
            row += bytes([0x22, 0x55, 0xAA, 0xFF]) if inside else bytes(4)
        rows.append(bytes(row))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(b"".join(rows)))
        + chunk(b"IEND", b"")
    )


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--packager", required=True)
    p.add_argument("--sample", required=True, type=pathlib.Path)
    p.add_argument("--face-name", required=True)
    p.add_argument("--title", default=None)
    p.add_argument("--out", required=True, type=pathlib.Path)
    p.add_argument("--base-url", required=True)
    p.add_argument("--marketplace", default="ee.schimke.watchface.push")
    p.add_argument("--wff-version", type=int, default=1)
    p.add_argument("--version-code", type=int, default=1)
    # A catalog face, as preview.coo.ee lists them: id catalog:<system>/<previewId>, attribution.
    p.add_argument("--catalog-system")
    p.add_argument("--catalog-title")
    p.add_argument("--preview-id")
    p.add_argument("--license", default="Apache-2.0")
    p.add_argument("--repo-url", default="https://github.com/android/wear-os-samples")
    a = p.parse_args()

    out = a.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    bundle = pathlib.Path(tempfile.mkdtemp(prefix="bundle-"))
    (bundle / "res/raw").mkdir(parents=True)
    (bundle / "res/xml").mkdir(parents=True)
    (bundle / "res/drawable").mkdir(parents=True)
    (bundle / "res/raw/watchface.xml").write_bytes((a.sample / "raw/watchface.xml").read_bytes())
    (bundle / "res/xml/watch_face_info.xml").write_bytes(
        (a.sample / "xml/watch_face_info.xml").read_bytes()
    )
    (bundle / "res/drawable/preview.png").write_bytes(png())
    (out / "preview.png").write_bytes(png(320))
    # Same shape as WffBundleLayout.manifestDocument; the packager rewrites package/versionCode.
    (bundle / "AndroidManifest.xml").write_text(
        f"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="ee.schimke.sample.{a.face_name}" android:versionCode="{a.version_code}"
    android:versionName="1.{a.version_code}">
  <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="34" />
  <uses-feature android:name="android.hardware.type.watch" />
  <application android:hasCode="false" android:label="{a.title or a.face_name}">
    <property android:name="com.google.wear.watchface.format.version"
        android:value="{a.wff_version}" />
  </application>
</manifest>
"""
    )

    # A throwaway keystore for this run only: nothing signed here is published.
    keystore = out / "debug.p12"
    keystore.unlink(missing_ok=True)
    subprocess.run(
        ["keytool", "-genkeypair", "-keystore", str(keystore), "-storetype", "PKCS12",
         "-storepass", "android", "-keypass", "android", "-alias", "debug", "-keyalg", "RSA",
         "-keysize", "2048", "-validity", "30", "-dname", "CN=push-app emulator test"],
        check=True, capture_output=True,
    )
    apk = out / "face.apk"
    token_file = out / "token.txt"
    subprocess.run(
        [a.packager, "pack", "--bundle", str(bundle), "--out", str(apk),
         "--version-code", str(a.version_code), "--push-marketplace", a.marketplace,
         "--face-name", a.face_name, "--keystore", str(keystore), "--keystore-pass", "android",
         "--token-out", str(token_file)],
        check=True,
    )
    data = apk.read_bytes()
    base = a.base_url.rstrip("/")
    design = {
        "id": f"catalog:{a.catalog_system}/{a.preview_id}" if a.catalog_system else a.face_name,
        "title": a.title or a.face_name,
        "description": f"Packaged from the repository's {a.sample.name} sample by :wff-packager.",
        "previewUrl": f"{base}/preview.png",
        "version": a.version_code,
        "versionName": f"1.{a.version_code}",
        "wffVersion": a.wff_version,
        "packageName": f"{a.marketplace}.watchfacepush.{a.face_name}",
        # Like the server's: absolute and versioned; clients use it exactly as given.
        "apkUrl": f"{base}/face.apk?v={a.version_code}",
        "sha256": hashlib.sha256(data).hexdigest(),
        "sizeBytes": len(data),
        "validationToken": token_file.read_text().strip(),
    }
    if a.catalog_system:
        design.update(
            source="catalog",
            visibility="public",
            catalog={"system": a.catalog_system, "title": a.catalog_title or a.catalog_system},
            attribution={
                "repoUrl": a.repo_url,
                "sourceUrl": f"{a.repo_url}/tree/main/WatchFaceFormat/{a.sample.name}",
                "license": a.license,
            },
            description=f"The {a.sample.name} sample, packaged by :wff-packager.",
        )
    (out / "extra.json").write_text(json.dumps({"designs": [design]}, indent=2))
    print(json.dumps({k: v for k, v in design.items() if k != "validationToken"}, indent=2))


if __name__ == "__main__":
    main()
