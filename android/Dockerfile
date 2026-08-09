# syntax=docker/dockerfile:1
FROM docker.io/library/gradle:8.10.2-jdk17 AS gradle_runtime

FROM ghcr.io/cirruslabs/android-sdk:36 AS test

COPY --from=gradle_runtime /opt/gradle /opt/gradle
ENV PATH="/opt/gradle/bin:${PATH}"

WORKDIR /workspace
RUN sdkmanager "platforms;android-35" "build-tools;34.0.0"

COPY . .

RUN gradle --no-daemon testDebugUnitTest
RUN gradle --no-daemon lintDebug

FROM test AS build
ARG SIGNING_KEY_ID
RUN --mount=type=secret,id=parental_device_blocker_keystore,required=true \
    test -n "$SIGNING_KEY_ID" && \
    gradle --no-daemon assembleDebug \
      -Pandroid.injected.signing.store.file=/run/secrets/parental_device_blocker_keystore \
      -Pandroid.injected.signing.store.password=android \
      -Pandroid.injected.signing.key.alias=androiddebugkey \
      -Pandroid.injected.signing.key.password=android
