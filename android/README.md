# Android

Kotlin + Jetpack Compose Android app workspace.

M0 keeps the app as a minimal Compose shell. M3 adds the first real vertical slice: manual word add, +10 minute notification, notification actions, review event storage, and scheduler-driven due updates.

```sh
cd VocabReminderAndroid
./gradlew :app:assembleDebug
```

The local machine must have an Android SDK. If the SDK is outside the default location, create `VocabReminderAndroid/local.properties` with:

```properties
sdk.dir=/absolute/path/to/android-sdk
```
