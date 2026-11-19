# :mobile-app

**Faces** on the phone: the watch faces published on ui.coo.ee (the Watch Face Push site
[`:push-app`](../push-app/README.md) installs from), each one tap from the home screen as a
**Remote Compose launcher widget** on Android 17 (API 37).

## How a face becomes a widget

1. The app lists the site's public faces: `GET /api/watchface-push/v1/designs`, the same listing
   the watch shows before it is linked (`FaceCatalog`).
2. Choosing one pins a widget (`AppWidgetManager.requestPinAppWidget`), or sets the face of the
   widget being added or reconfigured from the launcher (`MainActivity.Configure`).
3. The widget asks a design server to compile the face to Remote Compose at the widget's size
   (`FaceRenderer`). It sends `POST /api/cards/resolve` with a `watchface` card that has
   `sweep: false` and `seconds: false`, then fetches the `.rc` from the same host and checks its
   SHA-256.
   - A face is Watch Face Format XML. Turning it into `.rc` needs compose-ai-tools'
     `dwf-remote-compose` translator and Skia, which run on a JVM, not on the phone.
   - The server is homeassistant-remotecompose's `design-server` (`WatchFaces`), at `ha.coo.ee` by
     default.
4. The document is published as `RemoteViews.DrawInstructions` through rc-players'
   `rc-widget-launcher-core` (`RcPlatformWidgets.remoteViews`). That call first adapts the document
   for the device's launcher player.
5. **Ticking.** A launcher draws a widget as of its last push: it reads the clock when it draws,
   not every second. So `FaceWidgetTicker` pushes each widget's cached document again at every
   whole minute, from disk and without the network.
   - That is why the second hand is left out: it would stand still between pushes.
   - `TIME_SET` and `TIMEZONE_CHANGED` push again at once.
   - Only a new face or a new widget size fetches anything (`FaceWidgetStore` keeps one document a
     size, in 32 px steps).

## Android versions

The widget is enabled from API 37 (`@bool/supports_face_widgets`):

- SimpleAnalog compiled this way folds for the API 37 launcher profile (`widgets-v7`) with no
  violations, at about 9 KB.
- Android 16's launcher player (level 6) refuses its `DRAW_TO_BITMAP`.
- Both are checked in homeassistant-remotecompose
  (`WatchFacesTest.aFaceWithoutSecondsPlaysAsAnAndroid17LauncherWidget`).
- On Android 16 the app still lists faces, but offers no widget.

## Build

```sh
build-brief ./gradlew :mobile-app:assembleDebug       # Android SDK platform 37
build-brief ./gradlew :mobile-app:testDebugUnitTest   # JDK 21 (Robolectric on SDK 36)
```

| Property | Default | |
|---|---|---|
| `-Pfaces.server` | `https://ui.coo.ee` | where faces are listed |
| `-Pfaces.renderer` | `https://ha.coo.ee` | the design server that compiles them |

The app shares the watch app's applicationId (`watchface.marketplacePackage`), as the phone and
Wear builds of one Play listing do. It is not published to Play yet.

## Not done yet

- **Not run on a device or emulator.** The tests cover the listing and renderer clients, the store,
  the minute alignment and the widget sizing; no launcher has drawn a face yet.
- **The renderer must serve watch faces.** Watch face support has to be deployed at `ha.coo.ee`
  (homeassistant-remotecompose#1661) before the default renderer answers.
- **Private faces need a linked account,** as on the watch; only public faces are listed.
- **Install on the watch.** A phone could hand a face to the watch app over the Data Layer.
