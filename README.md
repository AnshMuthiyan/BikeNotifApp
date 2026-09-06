# E-Bike Weather App

An Android app that notifies you when you bike home and rain is expected soon, so you can bring your bike downstairs and keep it dry.

Package: `com.anshmuthiyan.ebikeweather`

Open the `EBikeWeatherApp` directory in Android Studio and sync Gradle dependencies.

## GitHub release workflow

The manual `Release Android App` workflow builds a signed `app-release.aab` and stores it as a GitHub Actions artifact. To enable it, add these repository secrets under **Settings > Secrets and variables > Actions**:

- `ANDROID_KEYSTORE_BASE64`: base64 contents of `C:\Users\anshm\bike-notif-release.jks`
- `BIKE_KEYSTORE_PASSWORD`
- `BIKE_KEY_ALIAS`
- `BIKE_KEY_PASSWORD`

Run the workflow from the **Actions** tab with `Publish to Google Play Internal testing` set to `false` while testing the build. After creating a Google Play service account and granting it Play Console release access, add its JSON as `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` and run the workflow with publishing enabled.

The package name is `com.anshmuthiyan.ebikeweather`. The Play Console app must be created with this exact package name before publishing.

Weather alerts use the first event on the next calendar day as the forecast cutoff. The app requests calendar read access when permissions are set up; if access is denied or no event exists tomorrow, it uses a 24-hour forecast window.
