#!/bin/sh
set -e

# Volumes and host folders are often created by root. Start as root only long enough to hand the two mount points to the
# unprivileged user, then run everything else (including the built-in PostgreSQL, which refuses to run as root) as that user.
# PUID and PGID (the convention of most self-hosting images, default 1000) pick the user id and group id it runs as,
# so files on a shared disk keep the owner you want.
if [ "$(id -u)" = 0 ]; then
    PUID="${PUID:-1000}"
    PGID="${PGID:-1000}"
    [ "$PGID" = "$(id -g tsundoku)" ] || groupmod -o -g "$PGID" tsundoku
    [ "$PUID" = "$(id -u tsundoku)" ] || usermod -o -u "$PUID" tsundoku
    for dir in /data /data/downloads /home/tsundoku; do
        mkdir -p "$dir"
        [ "$(stat -c %u:%g "$dir")" = "$PUID:$PGID" ] || chown "$PUID:$PGID" "$dir"
    done
    export HOME=/home/tsundoku
    exec setpriv --reuid="$PUID" --regid="$PGID" --init-groups "$0" "$@"
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
