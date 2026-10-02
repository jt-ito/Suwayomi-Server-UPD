#!/bin/sh
set -e

# The WebUI that was built into the image replaces the copy in the data volume on every start, so an image update
# also updates the interface (the volume would otherwise keep serving an old one).
rm -rf /data/webUI
cp -r /opt/tsundoku/webUI /data/webUI

# Settings come from the environment variables listed in the README (AUTH_MODE, BIND_PORT, DATABASE_TYPE, ...).
# shellcheck disable=SC2086
exec java \
    -Djava.awt.headless=true \
    -Dsuwayomi.tachidesk.config.server.rootDir=/data \
    -Dsuwayomi.tachidesk.config.server.systemTrayEnabled=false \
    -Dsuwayomi.tachidesk.config.server.initialOpenInBrowserEnabled=false \
    $JAVA_OPTS \
    -jar /opt/tsundoku/tsundoku.jar
