# Android

Kotlin + Jetpack Compose Android app workspace.

M3 adds the first real vertical slice: manual word add, +10 minute notification, notification actions, review event storage, and scheduler-driven due updates. M4/M6/M7 add capture preview, share intent, clipboard button, local dictionary fallback, settings, queue rebalance, quiet-hours sweep, and recommendation intro actions.

```sh
cd VocabReminderAndroid
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The local machine must have an Android SDK. If the SDK is outside the default location, create `VocabReminderAndroid/local.properties` with:

```properties
sdk.dir=/absolute/path/to/android-sdk
```
