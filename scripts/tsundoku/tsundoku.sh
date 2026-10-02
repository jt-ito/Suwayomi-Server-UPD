#!/bin/sh
# Starts tsundoku with the Java runtime and the tsundoku-WebUI that came in this folder (Linux and macOS).
cd "$(dirname "$0")" || exit 1

case "$(uname -s)" in
    Darwin) DATA="$HOME/Library/Application Support/Tachidesk" ;;
    *) DATA="${XDG_DATA_HOME:-$HOME/.local/share}/Tachidesk" ;;
esac

# The bundled interface replaces the copy in the data folder on every start, so an update also updates the interface.
mkdir -p "$DATA"
rm -rf "$DATA/webUI"
cp -R webUI "$DATA/webUI"

exec ./jre/bin/java -Dsuwayomi.tachidesk.config.server.webUIFlavor=CUSTOM -jar tsundoku.jar "$@"
