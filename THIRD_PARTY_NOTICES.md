# Third-party notices

Paperis original source code, documentation and authored examples are licensed under [MIT](LICENSE). Third-party software retains its own copyright and license. The following components are used by this source preview; most are downloaded by the build rather than stored in this repository.

| Component | Use | License / source |
| --- | --- | --- |
| Gradle 8.13 Wrapper | Checked-in launcher scripts and wrapper JAR; downloads the Gradle distribution | [Apache License 2.0](licenses/Apache-2.0.txt); [Gradle source](https://github.com/gradle/gradle/tree/v8.13.0) |
| Kotlin 2.2.0 standard library | Application runtime dependency | Apache License 2.0; [Kotlin source](https://github.com/JetBrains/kotlin/tree/v2.2.0) |
| Kotlin Gradle plugin 2.2.0 | Build tooling | Apache License 2.0; [Kotlin source](https://github.com/JetBrains/kotlin/tree/v2.2.0) |
| Android Gradle Plugin 8.13.2 | Build tooling | Apache License 2.0; [Android tools source](https://android.googlesource.com/platform/tools/base/) |
| Android APK Signature Scheme library 8.13.2 | Cryptographic verification before handing an update to the Android installer | Apache License 2.0; [apksig source](https://android.googlesource.com/platform/tools/apksig/) |
| AndroidX Core 1.17.0 | Read-only, permission-granted private update APK URI | Apache License 2.0; [AndroidX source](https://android.googlesource.com/platform/frameworks/support/) |
| JetBrains Java annotations | Transitive annotations dependency | Apache License 2.0; [source](https://github.com/JetBrains/java-annotations) |
| JUnit 4.13.2 | JVM tests only | Eclipse Public License 1.0; [license and source](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt) |
| Hamcrest Core 1.3 | Transitive JVM test dependency | BSD 3-Clause; [license and source](https://github.com/hamcrest/JavaHamcrest/blob/hamcrest-java-1.3/LICENSE.txt) |
| Gunicorn 25.3.0 | Feedback service hosting dependency; not included in the APK | [MIT license and source](https://github.com/benoitc/gunicorn/blob/master/LICENSE) |

| Tesseract4Android 4.9.0 | Native page/line geometry for scanned PDFs | Apache 2.0; [source](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0), [license](licenses/ocr/Tesseract4Android-LICENSE.txt) |
| Tesseract 5.5.1 | Local page segmentation and fallback recognition | Apache 2.0; [source](https://github.com/tesseract-ocr/tesseract/tree/5.5.1), [license](licenses/ocr/Tesseract-LICENSE.txt) |
| Leptonica 1.85.0 | OCR image processing | BSD-style; [license](licenses/ocr/Leptonica-LICENSE.txt) |
| IJG JPEG 9f | Native image decoding | IJG license; [copyright and conditions](licenses/ocr/libjpeg-README.txt) |
| libpng 1.6.48 | Native image decoding | libpng license; [license](licenses/ocr/libpng-LICENSE.txt) |
| AndroidX annotations 1.3.0 | Transitive native wrapper annotations | Apache 2.0; [source](https://android.googlesource.com/platform/frameworks/support/+/refs/tags/androidx-annotation-annotation-1.3.0/) |
| Tesseract English fast / Chinese best traineddata | Bundled offline segmentation/recognition data | Apache 2.0; [fast models](https://github.com/tesseract-ocr/tessdata_fast), [best models](https://github.com/tesseract-ocr/tessdata_best); exact revisions and SHA-256 in `assets/ocr/models.json` |
| PP-OCRv5 server recognition model and dictionary | Full-precision local Chinese/English line recognition | Apache 2.0; [official model](https://huggingface.co/PaddlePaddle/PP-OCRv5_server_rec_onnx/tree/b70df217f4fd99d14f970bad092cebe7d74cc4d1), [license](licenses/ocr/PaddleOCR-LICENSE.txt); model and dictionary checksums in `assets/ocr/models.json` |
| ONNX Runtime Android 1.23.2 | CPU-only native model inference | MIT; [license](licenses/ocr/ONNX-Runtime-LICENSE.txt), [third-party notices](licenses/ocr/ONNX-Runtime-ThirdPartyNotices.txt) |

Gradle distributions and build tools contain additional components with their own notices, supplied with those distributions. The Apache 2.0 text is included in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt); existing copyright headers in the wrapper scripts are preserved.

Android Canvas, AGSL, text layout, PDF APIs, system file pickers and system fonts are supplied by the device platform. This repository does not redistribute the Android SDK or system font files. Custom fonts and imported books are user-provided local content and are not relicensed by Paperis.

The outlined launch slogans were typeset with [Noto Serif CJK SC Regular](https://github.com/notofonts/noto-cjk/tree/main/Serif), copyright © 2017–2023 Adobe, licensed under the [SIL Open Font License 1.1](https://github.com/notofonts/noto-cjk/blob/main/Serif/LICENSE). These are fixed vector text artwork; no font file is bundled.

The text and document fixtures in `android/app/src/androidTest/assets/fixtures/` are original minimal testing examples covered by the project MIT license. The test-only fixture provider can copy a device font into its temporary test storage; that font is not shipped in this repository or in the production app.

No BOOX, Hanvon or Xteink product photographs, interface artwork or branding are included. Paperis is an independent project.

The PP-OCRv5 recognition model is redistributed as the official full-precision ONNX file. Tesseract traineddata retains the upstream best/fast variants listed above; no models are fine-tuned by this project. The dictionary is serialized from the official model configuration, preserving order and characters. The Android APK includes these OCR license texts under `assets/ocr/licenses/`. Source books and page images are not uploaded by either OCR engine.
