---
title: Faces — Privacy Policy
permalink: /privacy/
---

# Faces — Privacy Policy

_Last updated: 2026-11-18_

Faces: watch face installer for Wear OS (`ee.schimke.watchface.push`) is built and maintained by
Yuri Schimke (`yuri@schimke.ee`). Source: <https://github.com/yschimke/watchface-tools>
(`push-app/`).

## What the app does

Faces installs watch faces you published on a watch-face design site onto your Wear OS watch. You
link the watch to your account on that site by scanning a code on your phone or computer; nothing
is typed on the watch.

## Data the app sends

The app talks only to the design site you choose. It starts on `ui.coo.ee`, offers
`preview.coo.ee`, and you can enter another site's address yourself. A site you enter is run by
whoever runs it, under its own privacy policy.

- **Linking:** your watch's model name (for example "Pixel Watch 4") and Wear OS version, so the
  site can show which device is asking to link.
- **Listing and installing designs:** a device token that the site issued when you linked, sent
  with each request so the site returns your designs.
- **Install results:** after an install, which design and version was installed, whether it
  succeeded, and an error type if it failed, along with the Wear OS version. The site uses this to
  show install status and to fix failures.

All requests use HTTPS. The app does not include analytics, crash reporting or advertising SDKs,
does not use the advertising ID, and does not read your location, contacts, health data or other
apps' data.

## Data stored on the watch

The device token, the linked account's display name and the chosen site address are stored on the
watch. The token is encrypted with an Android Keystore key. Downloaded watch faces are installed
through Wear OS's Watch Face Push API.

## Deleting your data

Unlink the watch in the app, or remove the device on the design site, to revoke its token.
Uninstalling the app deletes everything it stored on the watch. To have data held by `ui.coo.ee`
or `preview.coo.ee` deleted, email the address below.

## Children

The app is not directed at children under 13.

## Contact and changes

For privacy questions contact `yuri@schimke.ee`, or open an issue at
<https://github.com/yschimke/watchface-tools/issues>.
Policy changes are recorded in
[git history](https://github.com/yschimke/watchface-tools/commits/main/docs/privacy.md).
