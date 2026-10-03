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

# DATABASE picks the engine from the very first start, so nothing has to be migrated later:
#   h2        the embedded H2 file database (the default)
#   postgres  the PostgreSQL that is built into the server (kept in /data/postgres-data)
#   external  your own PostgreSQL: also set DATABASE_URL, DATABASE_USERNAME and DATABASE_PASSWORD
# It only fills in DATABASE_TYPE and USE_EMBEDDED_POSTGRES when they are not set explicitly.
case "$(printf '%s' "${DATABASE:-}" | tr 'A-Z' 'a-z')" in
    "") ;;
    h2)
        : "${DATABASE_TYPE:=H2}"
        export DATABASE_TYPE
        ;;
    postgres | embedded | builtin)
        : "${DATABASE_TYPE:=POSTGRESQL}"
        : "${USE_EMBEDDED_POSTGRES:=true}"
        export DATABASE_TYPE USE_EMBEDDED_POSTGRES
        ;;
    external)
        : "${DATABASE_TYPE:=POSTGRESQL}"
        : "${USE_EMBEDDED_POSTGRES:=false}"
        export DATABASE_TYPE USE_EMBEDDED_POSTGRES
        ;;
    *)
        echo "ERROR: DATABASE must be h2, postgres or external (got \"$DATABASE\")." >&2
        exit 1
        ;;
esac
# The built-in PostgreSQL starts empty. If the folder already holds a library in H2, starting on PostgreSQL would show an
# empty library (the H2 file stays untouched, but it would look as if everything was gone), so refuse unless asked to.
# This covers DATABASE=postgres as well as DATABASE_TYPE=POSTGRESQL with USE_EMBEDDED_POSTGRES=true.
if [ "$(printf '%s' "${DATABASE_TYPE:-}" | tr 'a-z' 'A-Z')" = "POSTGRESQL" ]     && [ "$(printf '%s' "${USE_EMBEDDED_POSTGRES:-}" | tr 'A-Z' 'a-z')" = "true" ]     && [ -f /data/database.mv.db ] && [ ! -d /data/postgres-data ]; then
    echo "ERROR: /data already holds a library in H2 (database.mv.db), and the built-in PostgreSQL would start empty." >&2
    echo "ERROR: To keep your library: start without DATABASE / DATABASE_TYPE / USE_EMBEDDED_POSTGRES (H2), then open /database and migrate." >&2
    echo "ERROR: To start with an empty PostgreSQL anyway (the H2 file is kept), set DATABASE_ALLOW_EMPTY=true." >&2
    if [ "$(printf '%s' "${DATABASE_ALLOW_EMPTY:-}" | tr 'A-Z' 'a-z')" != "true" ]; then
        exit 1
    fi
fi
if [ "$(printf '%s' "${DATABASE_TYPE:-}" | tr 'a-z' 'A-Z')" = "H2" ] && [ -d /data/postgres-data ] && [ ! -f /data/database.mv.db ]; then
    echo "WARNING: /data holds a PostgreSQL library (postgres-data) but the H2 database is selected, which starts empty." >&2
    echo "WARNING: To keep that library, use DATABASE=postgres, or move it with the migration page (/database)." >&2
fi

# Chromium (the WebView) locks its profile with the hostname of the container that used it, and a recreated container has
# a new hostname, so the old lock would stop the WebView from starting. Only one container uses a data folder, so the
# lock files left behind are stale.
find /data/cache -maxdepth 4 \( -name SingletonLock -o -name SingletonCookie -o -name SingletonSocket \) -delete 2>/dev/null || true

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
