#!/usr/bin/env bash
# Build helper: Android Studio's bundled JBR 21, not the box-default JDK.
export JAVA_HOME="D:/Program Files/Android-Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")" || exit 1
exec ./gradlew "$@"
