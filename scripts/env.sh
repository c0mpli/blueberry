# Source before anything else. The JDK is keg-only, so JAVA_HOME is mandatory —
# `java` is not on PATH and gradlew/sdkmanager both die without it.
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$PATH"
export ADB_SERIAL="${ADB_SERIAL:-emulator-5554}"   # override to target a phone: ADB_SERIAL=192.168.x.x:PORT
export PKG=com.blueberry
