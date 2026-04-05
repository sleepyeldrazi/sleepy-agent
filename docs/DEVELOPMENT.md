# Development Guide

## Building from Source

### Prerequisites

- Android Studio Ladybug (2024.2.1) or newer
- JDK 17
- Android SDK with API 35
- A device or emulator running Android 8.0+ (API 26)

### Build Steps

1. **Clone the repository**
   ```bash
   git clone https://github.com/sleepyeldrazi/sleepy-agent.git
   cd sleepy-agent
   ```

2. **Open in Android Studio**
   - Open the project folder in Android Studio
   - Let Gradle sync complete

3. **Build Debug APK**
   ```bash
   ./gradlew :app:assembleDebug
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`

4. **Build Release APK**
   ```bash
   ./gradlew :app:assembleRelease
   ```
   Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

   To sign the release APK:
   ```bash
   # Generate a keystore (one-time)
   keytool -genkey -v -keystore my-release-key.keystore -alias sleepyagent -keyalg RSA -keysize 2048 -validity 10000

   # Sign the APK
   jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 -keystore my-release-key.keystore app-release-unsigned.apk sleepyagent

   # Align the APK
   zipalign -v 4 app-release-unsigned.apk sleepy-agent-signed.apk
   ```

## Adding Your SearXNG Server

To set up a SearXNG server, refer to the [SearXNG documentation](https://github.com/searxng/searxng) or [Docker setup guide](https://docs.searxng.org/admin/installation-docker.html).

By default, the app allows cleartext (HTTP) connections to:
- `sleepy-think` (local hostname)
- `192.168.1.100` (local network)
- `localhost`

If your SearXNG server uses a different IP or hostname, you need to update the network security config.

### Step 1: Edit `network_security_config.xml`

File: `app/src/main/res/xml/network_security_config.xml`

Add your domain or IP to the `<domain includeSubdomains="true">` list:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system"/>
        </trust-anchors>
    </base-config>
    
    <!-- Allow cleartext to local/SearXNG servers -->
    <domain-config cleartextTrafficPermitted="true">
        <!-- Default allowed domains -->
        <domain includeSubdomains="true">sleepy-think</domain>
        <domain includeSubdomains="true">192.168.1.100</domain>
        <domain includeSubdomains="true">localhost</domain>
        
        <!-- ADD YOUR SERVER HERE -->
        <domain includeSubdomains="true">192.168.1.50</domain>
        <domain includeSubdomains="true">my-searx-server</domain>
        <domain includeSubdomains="true">searx.mydomain.com</domain>
    </domain-config>
</network-security-config>
```

### Step 2: Rebuild and Install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Alternative: Use HTTPS

If you have HTTPS set up on your SearXNG server (recommended), no config changes are needed. Just enter the HTTPS URL in Settings:
```
https://your-searx-server.com
```

## Common Issues

### Gradle Sync Fails
- Make sure you're using JDK 17
- Check that Android SDK API 35 is installed

### App Crashes on Launch
- Check `adb logcat` for the specific error
- Common cause: Missing permissions or incompatible Compose version

### Model Won't Load
- Verify the model file is a valid `.litertlm` format
- Check that the file isn't corrupted (compare checksums if available)
- Ensure you have enough free RAM (4GB+ recommended)

### Web Search Not Working
- Verify your SearXNG server is accessible from the device
- Check that the server URL includes the protocol and port:
  - Correct: `http://192.168.1.100:8080`
  - Wrong: `192.168.1.100:8080`
- If using HTTP on a custom domain, make sure you added it to `network_security_config.xml`

## Project Structure

```
app/src/main/java/com/sleepy/agent/
├── MainActivity.kt              # Main entry point
├── SleepyAgentApplication.kt    # Application class
├── audio/                       # Audio recording, TTS, VAD
├── camera/                      # Camera capture
├── data/                        # Conversation storage
├── di/                          # Dependency injection
├── download/                    # Model download manager
├── inference/                   # LLM engine, Agent, Conversation
├── service/                     # Floating button service
├── settings/                    # User preferences
├── tools/                       # Web search, server tools
└── ui/                          # Screens, ViewModels, Theme
```

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b my-feature`
3. Commit your changes: `git commit -am 'Add new feature'`
4. Push to the branch: `git push origin my-feature`
5. Open a Pull Request

## License

MIT License - See LICENSE file
