#!/bin/sh
set -e

# Volumes and host folders are often created by root. Start as root only long enough to hand the two mount points to the
# unprivileged user, then run everything else (including the built-in PostgreSQL, which refuses to run as root) as that user.
if [ "$(id -u)" = 0 ]; then
    for dir in /data /data/downloads; do
        mkdir -p "$dir"
        [ "$(stat -c %u "$dir")" = 1000 ] || chown 1000:1000 "$dir"
    done
    export HOME=/home/tsundoku
    exec setpriv --reuid=1000 --regid=1000 --init-groups "$0" "$@"
fi

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
