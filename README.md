# Aemu Postoffice for Android

A standalone Android-hosted implementation of the **Aemu Postoffice** service for PSP ad-hoc multiplayer. It brings the relay, ad-hoc control, and HTTP status components together in an Android app, so a compatible PSP emulator or client can connect to a server running on an Android device.

> **Project status:** This repository is an Android port and companion implementation of [Kethen/aemu_postoffice](https://github.com/Kethen/aemu_postoffice). Treat it as community software: review and validate it in your own environment before exposing a server to the public internet. It is not an official Sony, PPSSPP, or Google product.

## Capabilities

- Start, stop, and restart the server through an Android foreground service, with an ongoing notification and a stop action.

- Run the ad-hoc control service, relay service, and HTTP status endpoint from one device.

- View dashboard status, connected players, groups, relay sessions, and server logs.

- Edit and validate server configuration and the game-name/crosslink database; reset defaults and import or export JSON files.

- Filter, clear, and export logs; configure optional wake/Wi-Fi locks for longer-running sessions.

- Serve status and metadata endpoints plus a configurable static status page.

## Requirements

- Android Studio with a compatible Android SDK and JDK for the Gradle/Android Gradle Plugin versions configured in this repository.

- A physical Android device or emulator with network access. A physical device on a stable network is generally preferable for hosting.

- For remote players, a reachable public address or a suitable network path. Check firewall, carrier-grade NAT, and router behavior; port forwarding may be necessary depending on the host network.

The app requests network access, foreground-service, and wake-lock permissions. Android and device-vendor power-management policies can still stop or restrict background work; test behavior on the intended device and OS release.

## Build

Clone the repository and build the debug APK with the included Gradle wrapper:

```bash
git clone https://github.com/google-ai-studio-1/aemu_postoffice_android.git
cd aemu_postoffice_android
./gradlew assembleDebug
```

The APK is produced under `app/build/outputs/apk/debug/`. Open the project directory in Android Studio to inspect, install, or run the app. To run local unit tests:

```bash
./gradlew test
```

To run connected Android instrumentation tests on a configured device/emulator:

```bash
./gradlew connectedAndroidTest
```

Builds require downloading dependencies from the configured Google and Maven Central repositories. No Gemini API key is required by the server features documented here; `.env.example` is an optional AI Studio placeholder, not a server configuration file.

## First run

1. Install and open the app on the Android host.

1. Review **Configuration** before starting. The bundled `app/src/main/assets/config.json` provides the initial configuration and defaults; app-managed settings are stored in the app's private files directory.

1. Confirm bind address, ports, metadata, and enabled services. Defaults include ad-hoc control on TCP/UDP `27312`, relay on TCP/UDP `27313`, and HTTP status on TCP `8888`. Make sure the selected ports are available and allowed by the host network/firewall.

1. Optionally edit the game database from the app or import a compatible JSON file.

1. Start the server from the dashboard. Keep the foreground-service notification visible while hosting; use its action or the app to stop the service.

1. Configure compatible clients to use the Android host's reachable address and matching server ports. Validate access from a second device/network rather than assuming that a local address is reachable over the internet.

### HTTP status endpoints

The status server exposes a web status page at `/`, static resources under `/assets`, live server state at `/data.json`, and server metadata at `/metadata.json`. The status page and assets are initialized from bundled assets; inspect the implementation and test the endpoints on the configured port before publishing them publicly.

## Configuration and operations

Use the in-app configuration editor as the primary way to change settings. The configuration format is JSON and follows the keys and validation implemented in `ServerConfig.kt`; game database data is parsed by `GameDb.kt`. Configuration changes may require a server restart. The UI reports validation/defaulting and restart behavior; review logs after applying changes.

For a public deployment:

- Change placeholder metadata such as the default website and administrator email.

- Restrict public exposure of the HTTP status endpoint if its operational details should not be public.

- Review session limits, queue limits, binding address, and capacity for the expected player load.

- Test startup, restart, network interruption, device sleep, and shutdown on the actual Android version and network.

- Monitor the dashboard and exported logs, and keep a recovery path for configuration/database files.

- Do not treat this README or the default configuration as a hardened deployment profile. No independent security audit or production SLA is stated by this repository.

## Repository layout

```
app/src/main/java/com/example/core/   Protocol, relay, ad-hoc control, HTTP, and config logic
app/src/main/java/com/example/service/ Android service, lifecycle coordination, and storage
app/src/main/java/com/example/ui/     Compose screens, view model, and theme
app/src/main/assets/                  Initial server config, game database, status page, and GPL text
app/src/test/                         JVM/Robolectric and core tests
app/src/androidTest/                  Android instrumentation tests
```

## Relationship to the upstream project

The upstream project documents the wire protocol, server behavior, ports, and hosting model: [Kethen/aemu_postoffice](https://github.com/Kethen/aemu_postoffice). This Android repository contains a Kotlin implementation of the server-side components; it is not the upstream C/C++ server distribution. Consult upstream documentation for protocol context, and verify Android-specific behavior against this repository's code and tests.

## License and third-party notices

This project includes and adapts code from [Kethen/aemu_postoffice](https://github.com/Kethen/aemu_postoffice), which states that its non-`ext/` project code is licensed under the **GNU General Public License, version 3 (GPL-3.0-only)**. The Android repository also bundles the GPL-3.0 license text at `app/src/main/assets/gpl-3.0.txt`. Accordingly, the covered port and derivative project code are identified here as GPL-3.0-only; use, modification, and redistribution are governed by the license text, not by this summary. Preserve the upstream attribution and applicable notices when redistributing.

This README does not relicense third-party components. Their own notices and license terms continue to apply; review dependency and asset provenance before distributing an APK. The repository's bundled font files, Android/Gradle dependencies, and any other third-party materials may have separate terms. If you redistribute binaries, provide the corresponding source and notices required by the applicable licenses. The full GPL text is included in the app assets and is available at [GNU GPL version 3](https://www.gnu.org/licenses/gpl-3.0.html).

## Disclaimer

Aemu Postoffice is provided “as is,” without warranty, to the extent permitted by applicable law. You are responsible for securing and operating any server you expose, complying with applicable software licenses, and ensuring that your use of network services is lawful.
