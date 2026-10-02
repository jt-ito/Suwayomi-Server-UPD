# tsundoku

A self-hosted manga reader server that runs [Mihon (Tachiyomi)](https://mihon.app/) extensions, with accounts, a first-run setup flow, a built-in PostgreSQL option and a faster in-app WebView.

**tsundoku is a fork of [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server).** It keeps Suwayomi's extension support, library, downloads, backups, trackers and APIs, and adds the features below. The web interface lives in [tsundoku-WebUI](https://github.com/jt-ito/tsundoku-WebUI), a fork of [Suwayomi-WebUI](https://github.com/Suwayomi/Suwayomi-WebUI).

> [!NOTE]
> This is an independent fork maintained by one person. It is not affiliated with the Suwayomi project, and Suwayomi's own support channels do not cover it. Please report problems here.

## Table of contents
- [What is different from Suwayomi](#what-is-different-from-suwayomi)
- [Getting started](#getting-started)
- [First run and logging in](#first-run-and-logging-in)
- [Security](#security)
- [Database: H2, built-in PostgreSQL and the migration page](#database-h2-built-in-postgresql-and-the-migration-page)
- [WebView](#webview)
- [Backups](#backups)
- [Configuration and Docker environment variables](#configuration-and-docker-environment-variables)
- [Moving over from Suwayomi](#moving-over-from-suwayomi)
- [Development](#development)
- [Credit and license](#credit-and-license)

## What is different from Suwayomi

| Area | Suwayomi | tsundoku |
| --- | --- | --- |
| Authentication | Off by default, one shared login | **On by default** (`ui_login`), real accounts, first-run setup page |
| Users | Single user | **Multiple accounts** (admin and member), each with their own library, categories, reading progress and trackers |
| Database | H2, or an external PostgreSQL you run yourself | H2 or a **built-in PostgreSQL** that the server starts, upgrades and protects for you |
| Switching databases | Manual | **Guided migration page** (`/database`) with an automatic backup, both directions |
| WebView | Pictures of a server-side Chromium over a websocket | **WebRTC video** with automatic fallback, ad blocking, phone friendly |
| Backups | Mihon-compatible | Also keeps **extensions and repositories**, installs missing extensions on restore, stays readable by the official server |
| Sessions | 60 day refresh token | 180 day refresh token, silently refreshed, so a phone stays logged in |
| Updates | Suwayomi releases | Checks **this fork's** releases |

### Accounts
- Admins and members, created and managed from the WebUI (profile card in the sidebar).
- Library entries, categories, read progress and tracker bindings are per account. Existing single-user data moves to the first admin automatically.
- A user's role and existence are read from the database on every token check and refresh, so demoting or deleting an account takes effect immediately rather than when the token expires.

## Getting started

Download an archive from the [releases page](https://github.com/jt-ito/tsundoku/releases), unpack it and start the launcher:

| Platform | Archive | Start |
| --- | --- | --- |
| Windows (x64) | `tsundoku-<version>-windows-x64.zip` | `tsundoku.bat` |
| macOS (Apple silicon) | `tsundoku-<version>-macOS-arm64.tar.gz` | `tsundoku.command` |
| macOS (Intel) | `tsundoku-<version>-macOS-x64.tar.gz` | `tsundoku.command` |
| Linux (x64) | `tsundoku-<version>-linux-x64.tar.gz` | `tsundoku.sh` |

Each archive brings its own Java runtime and the tsundoku interface, so nothing else needs installing. The archives are not signed: Windows SmartScreen and macOS Gatekeeper ask for confirmation the first time. There is also a plain `tsundoku-<version>.jar` (needs JDK 21), and a [Docker image](#docker). Then open `http://localhost:4567`. Installers (.msi, AppImage, .deb) are not offered yet.

The server checks the releases page for updates and treats "no release yet" as "up to date".

To build from source you need JDK 21:

```bash
./gradlew :server:run          # run from source, serves on http://localhost:4567
./gradlew :server:shadowJar    # or build a runnable jar (server/build)
```

### Docker

```bash
docker run -d --name tsundoku -p 4567:4567 -v tsundoku-data:/data jteaito/tsundoku:latest
```

or use [docker-compose.yml](docker-compose.yml). Everything lives in the `/data` volume. The image runs as an unprivileged user (uid 1000 by default; set `PUID` and `PGID` to change it), bundles [tsundoku-WebUI](https://github.com/jt-ito/tsundoku-WebUI) (refreshed on every start), and is configured with the environment variables [below](#configuration-and-docker-environment-variables). To build it yourself:

```bash
docker build -t tsundoku .    # needs the .git folder; the WebUI comes from the fork-customizations branch of tsundoku-WebUI
```

Use `--build-arg WEBUI_REPO=...` and `--build-arg WEBUI_REF=...` to build another WebUI. The image is published to Docker Hub ([`jteaito/tsundoku`](https://hub.docker.com/r/jteaito/tsundoku)) and the GitHub Container Registry (`ghcr.io/jt-ito/tsundoku`) by `.github/workflows/docker_publish.yml` (a `v*` tag gives `:latest` and the version, a push to `master` gives `:edge`). The WebView needs the Chromium libraries the image installs; if it does not start on your host, set `KCEF_ENABLED=false`.

### Data folder

Data lives in the same folder as Suwayomi's (`%LOCALAPPDATA%\Tachidesk` on Windows, `~/.local/share/Tachidesk` on Linux, `~/Library/Application Support/Tachidesk` on macOS), so an existing library is picked up.

## First run and logging in

Authentication is on by default, so there has to be a way to create the first admin without shipping a default password. There isn't one:

- A fresh install has **no usable account**. The first visit redirects to `/setup`, where you choose the admin username and password (8 characters or more).
- Opened **from the machine the server runs on**, the setup page asks for nothing else.
- Opened from **anywhere else** (another device, a domain, a reverse proxy, Docker), it asks for a one-time **setup code** that the server prints to its log on startup. This stops a stranger who finds the port first from claiming the server.
- To skip the page entirely, set `AUTH_USERNAME` and `AUTH_PASSWORD` (see [below](#configuration-and-docker-environment-variables)). The admin account is created from them at startup, once.
- To run **without** authentication, set `AUTH_MODE=none` (or `server.authMode = "none"`). The server logs a warning on every start while auth is off, because anyone who can reach it then has full admin access. If you turn authentication on later, `/setup` opens.

Existing installs keep whatever `authMode` their `server.conf` already says.

## Security

What was changed beyond turning authentication on by default. This is hardening work by the fork's maintainer plus a security review of the changes; it has not had an independent audit.

- **No default credentials.** The first account is created by the owner through `/setup` or from configuration. The migration creates the account row with an empty password hash that cannot log in.
- **Setup page protections.** The setup code is compared in constant time. The code is skipped only for a request that provably comes from the machine's own browser: loopback peer, loopback `Host`, no proxy headers and a same-origin fetch. That blocks a cross-site form post or DNS rebinding from creating the admin. Any other request, including your own domain, only has to enter the code, so no domain is ever refused.
- **Output and script safety.** Values are escaped by the template engine, the setup page ships a Content-Security-Policy with a per-response nonce, `X-Frame-Options: DENY`, `nosniff` and no-referrer, and the old login page no longer follows `javascript:` or `//host` redirects.
- **Tokens.** Roles and account existence come from the database, not the token. Access tokens last 5 minutes and are refreshed by the WebUI; the refresh token lasts 180 days. Both lifetimes are configurable.
- **Built-in PostgreSQL** is protected by a randomly generated password (see below).
- **Tests** run against an isolated data folder and never touch your real configuration.

Known limits: CORS reflects the caller's origin, which is needed so a WebUI served from another domain can reach the API, and which only matters while `authMode` is `none`. Refresh tokens are not revoked when a password changes; deleting the account does revoke them. If you expose the server to the internet, keep authentication on and put it behind HTTPS.

## Database: H2, built-in PostgreSQL and the migration page

The default is the embedded **H2** file database, as in Suwayomi. tsundoku can also run its own **PostgreSQL**, with no separate install:

- **Built-in PostgreSQL.** The PostgreSQL binaries ship inside the server, so there is nothing extra to install. It starts PostgreSQL on a free local port, keeps its data in the data folder, protects it with a random password (stored next to the data, not in `server.conf`) and stops it with the server.
- **Automatic major-version upgrades.** When a new server release bundles a newer PostgreSQL, the old data is upgraded in a staging copy and a backup is kept. If anything fails, the original data is left untouched.
- **Migration page.** Open `/database` (also linked from Settings > Server > Database). It shows which engine is active and walks you through switching **from H2 to PostgreSQL and back**, with a confirmation dialog and an automatic backup first.
- **External PostgreSQL** works as before: set `DATABASE_TYPE=POSTGRESQL` and the `DATABASE_*` variables.

PostgreSQL is a good choice for larger libraries. H2 is simpler and fine for a personal library.

## WebView

The WebView opens a page in a Chromium that runs on the server (via KCEF), for sources that need a real browser to pass a challenge. In tsundoku it:

- streams the page as **WebRTC video** instead of JPEG pictures, with the old JPEG stream as an automatic fallback;
- uses **less CPU** while idle;
- blocks known ad and tracker domains (switchable in settings);
- works on **phones**, and is themed with the app;
- exposes `GET /api/v1/webview/user-agent` and `POST /api/v1/webview/cookies` so a native client can use the same user agent and import cookies.

Clients on other networks may need STUN/TURN servers: set `TSUNDOKU_WEBRTC_ICE_SERVERS` to a comma-separated list of URLs. KCEF is not supported on macOS.

## Backups

Mihon-compatible backups, plus:

- an option to include **extensions and repositories**;
- **missing extensions are installed automatically** while restoring;
- backups stay **readable by the official Suwayomi server**;
- the WebUI keeps you logged in after a restore and applies the restored theme without a reload.

## Configuration and Docker environment variables

Settings live in `server.conf` in the data folder and in Settings > Server. `-D` overrides (`-Dsuwayomi.tachidesk.config.server.<setting>=...`) still work. For containers, these environment variables set the matching setting and **win over `server.conf`** (empty values are ignored; lists and maps use JSON-style syntax):

`BIND_IP`, `BIND_PORT`, `SOCKS_PROXY_ENABLED/VERSION/HOST/PORT/USERNAME/PASSWORD`, `AUTH_MODE`, `AUTH_USERNAME`, `AUTH_PASSWORD`, `JWT_AUDIENCE`, `JWT_TOKEN_EXPIRY`, `JWT_REFRESH_EXPIRY`, `DEBUG`, `MAX_LOG_FILES`, `MAX_LOG_FILE_SIZE`, `MAX_LOG_FOLDER_SIZE`, `WEB_UI_ENABLED/FLAVOR/CHANNEL/UPDATE_INTERVAL`, `DOWNLOAD_AS_CBZ`, `DOWNLOAD_CONVERSIONS`, `EXTENSION_STORES`, `MAX_SOURCES_IN_PARALLEL`, `UPDATE_INTERVAL`, `BACKUP_TIME`, `BACKUP_INTERVAL`, `BACKUP_TTL`, `AUTO_BACKUP_INCLUDE_MANGA/CATEGORIES/CHAPTERS/TRACKING/HISTORY/CLIENT_DATA/SERVER_SETTINGS`, `FLARESOLVERR_ENABLED/URL/TIMEOUT/SESSION_NAME/SESSION_TTL/RESPONSE_AS_FALLBACK`, `DATABASE_TYPE/URL/USERNAME/PASSWORD`, `USE_EMBEDDED_POSTGRES`, `USE_HIKARI_CONNECTION_POOL`, `KCEF_ENABLED`. `TZ` is read by the JVM.

```yaml
environment:
  - AUTH_USERNAME=owner      # optional: skips the /setup page
  - AUTH_PASSWORD=change-me-please
  # - AUTH_MODE=none         # only if you really want no login
```

Differences from the old Docker defaults: `AUTH_MODE` defaults to `ui_login`, `JWT_REFRESH_EXPIRY` to `180d`, and the Docker image sets `WEB_UI_FLAVOR=CUSTOM` so it serves its bundled tsundoku-WebUI. Full reference: [docs/Configuring-Suwayomi‐Server.md](docs/Configuring-Suwayomi‐Server.md).

Running behind a reverse proxy (any domain works): pass WebSocket upgrades through, send `X-Forwarded-For`/`X-Forwarded-Proto`, terminate HTTPS at the proxy, and keep its access logs private, since the WebUI loads some images with the token in the URL.

## Moving over from Suwayomi

- Point tsundoku at your existing data folder, or restore a Suwayomi backup. Suwayomi's own backups can be restored here and tsundoku's can be restored there.
- Existing single-user data becomes the first admin's. Set `AUTH_USERNAME` and `AUTH_PASSWORD` (or use `/setup`) before you expose the server.
- The extension and tracker ecosystem is the same, so the same sources and tracking services work.

## Development

```bash
./gradlew :server:compileKotlin   # compile
./gradlew :server:test            # tests; they use an isolated data folder
./gradlew :server:ktlintCheck     # style
./gradlew :server:run             # run on :4567 (restart after Kotlin or .kte changes)
```

The server is Kotlin on JDK 21 with Javalin, GraphQL (graphql-kotlin), Exposed, JTE templates for the server-rendered pages (`/setup`, `/database`, the WebView page, `login.html`), and a bundled web interface from [tsundoku-WebUI](https://github.com/jt-ito/tsundoku-WebUI). See [CONTRIBUTING.md](CONTRIBUTING.md). Settings reference pages are in [docs/](docs/).

## Credit and license

tsundoku exists because of the [Suwayomi](https://github.com/Suwayomi) project and its contributors, whose server this is forked from. Suwayomi in turn is a spiritual successor of [TachiWeb-Server](https://github.com/Tachiweb/TachiWeb-server). The `AndroidCompat` module was originally developed by [@null-dev](https://github.com/null-dev) for TachiWeb-Server and parts of [Mihon (Tachiyomi)](https://github.com/mihonapp/mihon) are adopted into this codebase; both are licensed under the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0) (`Copyright 2015 Javier Tomás` for Mihon). Changes to both are licensed under MPL 2.0 like the rest of the project.

    Copyright (C) Contributors to the Suwayomi project

    This Source Code Form is subject to the terms of the Mozilla Public
    License, v. 2.0. If a copy of the MPL was not distributed with this
    file, You can obtain one at http://mozilla.org/MPL/2.0/.

## Disclaimer

The developer of this application does not have any affiliation with the content providers available.
