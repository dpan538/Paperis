# Third-party notices

Paperis original source code, documentation and authored examples are licensed under [MIT](LICENSE). Third-party software retains its own copyright and license. The following components are used by this source preview; most are downloaded by the build rather than stored in this repository.

| Component | Use | License / source |
| --- | --- | --- |
| Gradle 8.13 Wrapper | Checked-in launcher scripts and wrapper JAR; downloads the Gradle distribution | [Apache License 2.0](licenses/Apache-2.0.txt); [Gradle source](https://github.com/gradle/gradle/tree/v8.13.0) |
| Kotlin 2.2.0 standard library | Application runtime dependency | Apache License 2.0; [Kotlin source](https://github.com/JetBrains/kotlin/tree/v2.2.0) |
| Kotlin Gradle plugin 2.2.0 | Build tooling | Apache License 2.0; [Kotlin source](https://github.com/JetBrains/kotlin/tree/v2.2.0) |
| Android Gradle Plugin 8.13.2 | Build tooling | Apache License 2.0; [Android tools source](https://android.googlesource.com/platform/tools/base/) |
| JetBrains Java annotations | Transitive annotations dependency | Apache License 2.0; [source](https://github.com/JetBrains/java-annotations) |
| JUnit 4.13.2 | JVM tests only | Eclipse Public License 1.0; [license and source](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt) |
| Hamcrest Core 1.3 | Transitive JVM test dependency | BSD 3-Clause; [license and source](https://github.com/hamcrest/JavaHamcrest/blob/hamcrest-java-1.3/LICENSE.txt) |
| Gunicorn 25.3.0 | Feedback service hosting dependency; not included in the APK | [MIT license and source](https://github.com/benoitc/gunicorn/blob/master/LICENSE) |

Gradle distributions and build tools contain additional components with their own notices, supplied with those distributions. The Apache 2.0 text is included in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt); existing copyright headers in the wrapper scripts are preserved.

Android Canvas, AGSL, text layout, PDF APIs, system file pickers and system fonts are supplied by the device platform. This repository does not redistribute the Android SDK or system font files. Custom fonts and imported books are user-provided local content and are not relicensed by Paperis.

The text and document fixtures in `android/app/src/androidTest/assets/fixtures/` are original minimal testing examples covered by the project MIT license. The test-only fixture provider can copy a device font into its temporary test storage; that font is not shipped in this repository or in the production app.

No BOOX, Hanvon or Xteink product photographs, interface artwork or branding are included. Paperis is an independent project.
