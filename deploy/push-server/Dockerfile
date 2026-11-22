# syntax=docker/dockerfile:1.29

# Standalone HTTP service for Watch Face Format validation and Watch Face Push APK packaging.
# Ships `:push-server` (wrapping `:wff-packager` + Google's `validator-push`) with a minimal
# Android SDK (`build-tools` for aapt2/zipalign and `platforms` for android.jar) so releasing
# `watchface-tools` publishes a fresh image that Watchtower can roll without restarting the main
# preview server.

FROM eclipse-temurin:21.0.13.1_2-jdk AS sdk
ARG ANDROID_SDK_PLATFORM=android-36
ARG ANDROID_BUILD_TOOLS=36.0.0
ENV ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
RUN set -eu; \
    apt-get update && apt-get install -y --no-install-recommends curl ca-certificates unzip; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p "${ANDROID_HOME}/cmdline-tools"; \
    curl -fsSL -o /tmp/cmdtools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"; \
    unzip -q /tmp/cmdtools.zip -d "${ANDROID_HOME}/cmdline-tools"; \
    mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"; \
    rm /tmp/cmdtools.zip; \
    yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true; \
    "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" \
      "platforms;${ANDROID_SDK_PLATFORM}" \
      "build-tools;${ANDROID_BUILD_TOOLS}" >/dev/null; \
    mkdir -p "/opt/android-sdk-min/build-tools/${ANDROID_BUILD_TOOLS}" \
             "/opt/android-sdk-min/platforms/${ANDROID_SDK_PLATFORM}"; \
    cp -a "${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/aapt2" \
          "${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/zipalign" \
          "/opt/android-sdk-min/build-tools/${ANDROID_BUILD_TOOLS}/"; \
    if [ -d "${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/lib64" ]; then \
      cp -a "${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/lib64" \
            "/opt/android-sdk-min/build-tools/${ANDROID_BUILD_TOOLS}/"; \
    fi; \
    cp -a "${ANDROID_HOME}/platforms/${ANDROID_SDK_PLATFORM}/android.jar" \
          "/opt/android-sdk-min/platforms/${ANDROID_SDK_PLATFORM}/"

FROM eclipse-temurin:21.0.13.1_2-jre
COPY --from=sdk /opt/android-sdk-min /opt/android-sdk
COPY push-server/build/distributions/watchface-push-server*.tar.gz /tmp/
RUN set -eu; \
    apt-get update && apt-get install -y --no-install-recommends curl ca-certificates; \
    rm -rf /var/lib/apt/lists/*; \
    tar -xzf /tmp/watchface-push-server*.tar.gz -C /opt; \
    rm -f /tmp/watchface-push-server*.tar.gz; \
    if [ ! -d /opt/watchface-push-server ]; then \
      ln -s /opt/watchface-push-server-* /opt/watchface-push-server; \
    fi; \
    test -x /opt/watchface-push-server/bin/watchface-push-server; \
    mkdir -p /var/lib/watchface-push-server

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    WFF_PACKAGER_HOME=/var/lib/watchface-push-server \
    PATH="/opt/watchface-push-server/bin:${PATH}" \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/readyz || exit 1
ENTRYPOINT ["watchface-push-server"]
