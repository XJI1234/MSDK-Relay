# Launcher icon

The launcher uses the selected Android Studio default artwork: a white Android
robot on a green grid. The legacy 192 × 192 asset in
`src/main/res/drawable-xxxhdpi/ic_relay_launcher.webp` is cropped to remove the
original export's transparent padding (48 dp at xxxhdpi).

Android 8 and later resolve `drawable-anydpi-v26/ic_relay_launcher.xml` as an
adaptive icon. Its opaque 432 × 432 artwork places the selected image in the
central 288 × 288 visible window (72 dp within a 108 dp layer at xxxhdpi), with
the green grid extended into the outer bleed. The complete artwork stays in
the background layer to preserve the selected appearance; the foreground is
transparent. Normal and round launcher icons use the same system mask and size.

The Android robot is reproduced from work created and shared by Google and used
according to terms described in the [Creative Commons 3.0 Attribution License](https://creativecommons.org/licenses/by/3.0/).
See the [Android brand guidelines](https://developer.android.com/distribute/marketing-tools/brand-guidelines).
