FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jdk-headless ca-certificates curl unzip python3 openssl git libpulse0 libnss3 libx11-6 libxcb1 libxcomposite1 libxcursor1 libxi6 libxtst6 libxrandr2 libasound2t64 libgbm1 && rm -rf /var/lib/apt/lists/*
ENV PATH=/opt/android-sdk/cmdline-tools/19.0/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/emulator:$PATH
RUN curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o /tmp/cli.zip && echo '7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee  /tmp/cli.zip' | sha256sum -c - && mkdir -p /opt/android-sdk/cmdline-tools && unzip -q /tmp/cli.zip -d /tmp/sdk && mv /tmp/sdk/cmdline-tools /opt/android-sdk/cmdline-tools/19.0 && rm /tmp/cli.zip
RUN yes | sdkmanager --licenses >/dev/null && sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'emulator' 'system-images;android-35;google_apis;x86_64'
WORKDIR /workspace
COPY . .
CMD ["./gradlew", "--no-daemon", "testDebugUnitTest", "lintRelease", "assembleRelease"]
