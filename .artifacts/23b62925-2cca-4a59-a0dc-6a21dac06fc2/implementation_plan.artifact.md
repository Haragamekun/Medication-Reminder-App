# App Logo and Custom Splash Screen Implementation

This plan covers updating the app logo to match the provided icon and implementing a custom Splash Screen with a matching gradient background using the Jetpack SplashScreen API.

## User Review Required

> [!IMPORTANT]
> I will implement the **Jetpack SplashScreen API**, which is the modern way to handle splash screens in Android.
> I need you to ensure the image you provided is named `app_icon_main.png` and placed in the `app/src/main/res/drawable/` folder, as I cannot directly copy image files. I will set up the code to use this filename.

## Proposed Changes

### [Dependencies]

#### [MODIFY] [build.gradle.kts](file:///C:/Users/TUF/AndroidStudioProjects/OCRMedicationApp/app/build.gradle.kts)
- Add `androidx.core:core-splashscreen:1.0.1` dependency.

### [Assets & Drawables]

#### [NEW] [splash_background.xml](file:///C:/Users/TUF/AndroidStudioProjects/OCRMedicationApp/app/src/main/res/drawable/splash_background.xml)
- Create a linear gradient drawable matching the icon's background (Purple `#8A4E9A` to Deep Pink `#B25068`).

#### [MODIFY] [ic_launcher_background.xml](file:///C:/Users/TUF/AndroidStudioProjects/OCRMedicationApp/app/src/main/res/drawable/ic_launcher_background.xml)
- Replace the current background with the new gradient.

#### [MODIFY] [ic_launcher_foreground.xml](file:///C:/Users/TUF/AndroidStudioProjects/OCRMedicationApp/app/src/main/res/drawable/ic_launcher_foreground.xml)
- Update to use the new `app_icon_main` image.

### [Themes & Manifest]

#### [MODIFY] [themes.xml](file:///C:/Users/TUF/AndroidStudioProjects/OCRMedicationApp/app/src/main/res/values/themes.xml)
- Define `Theme.App.Starting` using `Theme.SplashScreen` as a parent.
- Set `windowSplashScreenBackground` to the new gradient.
- Set `windowSplashScreenAnimatedIcon` to the new icon.
- Set `postSplashScreenTheme` to the main app theme.

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/TUF/AndroidStudioProjects/OCRMedicationApp/app/src/main/AndroidManifest.xml)
- Set the `MainActivity` theme to `Theme.App.Starting`.

### [Code]

#### [MODIFY] [MainActivity.kt](file:///C:/Users/TUF/AndroidStudioProjects/OCRMedicationApp/app/src/main/java/com/example/ocrmedicationapp/MainActivity.kt)
- Call `installSplashScreen()` before `super.onCreate()`.

## Verification Plan

### Manual Verification
- Rebuild the app and verify the launcher icon has changed.
- Launch the app and verify the splash screen displays the new gradient and icon.
- Verify the transition from splash screen to the main app is smooth.
