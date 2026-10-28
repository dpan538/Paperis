# Contributing to Paperis 纸墨

欢迎参与 Paperis。请围绕本地书籍导入、格式支持、中文排版、字体与平板阅读体验提交具体改进。

显示、OCR、格式边界、本地保存与更新流程的技术说明见 [DEV.md](DEV.md)；本文保留构建、验证与贡献流程。

## Before you start

主要适配目标为小米 Pad 9 Pro Max；3408 × 2272、3:2 是项目参考配置。实际布局使用窗口、密度与安全边距，同时支持竖屏和较窄窗口。其他平板请通过独立分支与 PR 适配，并说明设备、Android / 系统版本、分辨率、显示密度、字体缩放与导航方式。

请保留整屏黑白灰、温润底材、分层墨迹和安静操作的方向。刷新应有明确边界，静止页面不应持续重绘；姿态传感器应在后台停止，系统关闭动画时应减少过渡。新增入口应有实际用途。

较大改动请先开 Issue 描述问题、预期行为、复现步骤与影响范围。解析问题优先提供你有权分享的最小样例；不要上传个人书库、商业电子书或未获授权的字体。

## Branches and pull requests

```sh
git clone https://github.com/YOUR_GITHUB_NAME/Paperis.git
cd Paperis
git remote add upstream https://github.com/dpan538/Paperis.git
git fetch upstream
git switch -c fix/your-change upstream/main
```

请先在 GitHub fork 项目，再以自己的分支向 `dpan538/Paperis:main` 提交 PR。保持改动聚焦，说明修改前后的行为、验证结果与尚未覆盖的情况。主分支由维护者合并；请勿重写共享历史或自行发布官方版本、标签与安装包。

## Build from source

准备 JDK 21、Android SDK Platform 36 与 Build Tools 36.0.0。项目附带 Gradle 8.13 Wrapper 和分发包 SHA-256 校验。配置 `JAVA_HOME`、`ANDROID_HOME`，或在 `android/local.properties` 中设置自己的 `sdk.dir`。

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 使用 `gradlew.bat`。首次构建需要下载 Gradle 和 Maven 依赖。调试 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`；构建产物不进入版本库。

新检出会使用 Android 工具生成的本机调试签名。维护者若保留被忽略的 `android/.tooling/inkreader-debug.keystore`，本地构建会沿用该预览签名。请勿提交密钥。签名不同的安装包不能直接覆盖已有安装，卸载前请自行保留需要的原文件。

当前 `applicationId` 为 `io.github.dpan538.inkreader.preview`，源码命名空间为 `com.inkreader.nativeink`，用于保持早期预览升级和本地状态兼容。应用显示名与 Gradle 项目名为 Paperis；请勿随普通适配改动包名。

## Verification

请只记录实际执行的检查，并明确区分模拟器与实机。按改动选择有意义的验证：

- 导入：系统选择、多选、文件夹、取消、重复文件、错误提示和原文件保持完整。
- 格式：编码、EPUB 书脊顺序、DOCX 段落，以及 PDF 文本层、纯扫描页 OCR、插图与不支持格式的边界。
- 排版：连续正文不丢失、不重复、不裁切；标点、段末孤字与跨页段落；连续调整字体、字号、行距、栏数和横竖屏后仍保留同一正文锚点。
- 平板：横竖屏、显示密度、系统边距、触控与菜单；导入字体及缺字回退。
- 屏幕：三种材质、刷新序列、快速连续操作、完成后无旧字残留、静止状态、关闭动画和后台传感器。
- 反馈：草稿恢复、失败保留、稳定重试标识与回执核验；测试使用模拟传输，不创建真实 Issue。
- 记录：每本书的阅读位置、重启恢复和简单书签。

项目包含 JVM 单元测试与原生仪器测试。屏幕整屏测量、分类检测、故障注入、修复反例和可重放矩阵可通过 `python3 tools/verify_reader.py --serial YOUR_EMULATOR_SERIAL --allow-reset-test-data --install` 执行；报告保存源位置、段落、排版坐标与修复规则，详见重排验证文档。仪器测试会重置阅读偏好并使用固定布局条件，请在专用模拟器或可清空的测试设备上运行。示例中的 `YOUR_TEST_SERIAL` 需替换为测试设备序列号。

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s YOUR_TEST_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s YOUR_TEST_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s YOUR_TEST_SERIAL shell am instrument -w io.github.dpan538.inkreader.preview.test/com.inkreader.nativeink.InkEvidence
```

导入与重排测试还包含一次系统 Downloads 文件选择，需要先放入仓库提供的原创文本样例：

```sh
adb -s YOUR_TEST_SERIAL push app/src/androidTest/assets/fixtures/system-picker.txt /sdcard/Download/InkReader-SAF.txt
adb -s YOUR_TEST_SERIAL shell am instrument -w -e suite imports io.github.dpan538.inkreader.preview.test/com.inkreader.nativeink.InkEvidence
```

核心交互检查使用 3408 × 2272、density 320、Android API 36 模拟器；`viewport` 套件另通过 WindowManager 改变实际分辨率、密度和系统字体缩放，并检查系统栏边距与短窗口菜单。该套件会修改并恢复专用测试设备的显示配置；系统文件选择步骤当前依赖英文 DocumentsUI，可按设备语言和文件管理器调整测试驱动。测试夹具 Provider 仅在测试 APK 中启用；截图、报告和设备日志保存在本地。PR 可附针对改动的脱敏图片和结果，不要把原始验收目录提交到仓库。

重排专用测试独立于截图比对，使用人工编写的逐字金样和横竖屏排版矩阵：

```sh
adb -s YOUR_TEST_SERIAL shell am instrument -w -e suite structure io.github.dpan538.inkreader.preview.test/com.inkreader.nativeink.InkEvidence
adb -s YOUR_TEST_SERIAL shell am instrument -w -e suite reflow io.github.dpan538.inkreader.preview.test/com.inkreader.nativeink.InkEvidence
adb -s YOUR_TEST_SERIAL shell am instrument -w -e suite experience io.github.dpan538.inkreader.preview.test/com.inkreader.nativeink.InkEvidence
adb -s YOUR_TEST_SERIAL shell am instrument -w -e suite viewport io.github.dpan538.inkreader.preview.test/com.inkreader.nativeink.InkEvidence
```

`reading-revision` 验证预设内冷暖/实际窗口亮度、菜单顺序、自然词间距和已合并错误的旧书副本；`experience` 覆盖导入入口、设置尺寸、语言切换、应用内更新状态和错误安装包拒绝、反馈草稿与回执、夜间灯光和深色模式。`updates` 用独立构建的同签名未来版本 APK 检查断点续传、完整 APK 验签、系统安装授权、私有文件 URI 交接和缓存重用；夹具以 `paperisVersionCode` / `paperisVersionName` Gradle 属性提版，仅放在测试设备 external-files 的 `update-fixture.apk`，不可发布。Release 附带 `paperis-update.json`，包含包名、versionCode、versionName、APK 文件名、SHA-256 与签名证书 SHA-256；客户端只接受同仓库、同签名、更高版本。运行 `updates` 前先从宿主 adb 将测试设备的 `REQUEST_INSTALL_PACKAGES` app-op 设为 `deny`，不要在仪器测试进程内撤销该权限（Android 会结束目标进程）。发布后使用 `update-channel` 核对当前安装版与真实 GitHub 版本接口；它会清理测试更新缓存，不能在个人设备运行。更多边界见 [重排验证](docs/REFLOW_TESTING.md)。

`anchors`、`documents`、`migration` 使用目标应用 external-files 的私有 `import-samples` 长书与独立期望配置。`anchors` 检查连续 288 次布局变化与真实旋转/系统字体缩放；`migration` 检查旧书本机修复和同书补充原件。私有书籍、配置、原始截图和报告不能提交。

反馈服务的本地测试与部署配置见 [feedback-service/README.md](feedback-service/README.md)。

## Commit messages

沿用 Baro 的提交习惯：英文主题说明具体变化，正文说明原因、行为与实际验证；可在末尾附简短中文说明。不要只写模糊标题、声称未执行的检查或重写已共享提交。

```text
Fix paragraph anchors after font changes

Preserve the source offset while rebuilding the page layout.
Validated with the font-switch instrumentation checks on API 36.

中文：字体变化后保留当前正文位置。
```

## What belongs in the repository

提交可复现的源代码、配置、原创最小测试样例与公开产品文档。不要提交签名密钥、令牌、个人书籍/字体、SDK、模拟器、构建缓存、APK、原始截图、讨论记录或本地路径与验收资料。

贡献的原创代码、文档和测试样例按 [MIT License](LICENSE) 提供。引入第三方材料时注明来源、用途及许可证，并更新 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

离线识别、重排与持久化回归：

```sh
adb -s YOUR_TEST_SERIAL shell am instrument -w -e suite library io.github.dpan538.inkreader.preview.test/com.inkreader.nativeink.InkEvidence
adb -s YOUR_TEST_SERIAL shell am instrument -w -e suite backup io.github.dpan538.inkreader.preview.test/com.inkreader.nativeink.InkEvidence
```

`backup` 会创建专用测试文件夹并通过英文 DocumentsUI 选择该目录。OCR 模型随包提供，首次构建从固定的 JitPack 坐标下载 Tesseract4Android，并从 Maven Central 下载 ONNX Runtime Android 1.23.2；模型来源、版本和 SHA-256 位于 `assets/ocr/models.json`。

## 正式版验收

正式 APK 使用 release 构建和项目既有签名；密钥不进入仓库。匹配的仪器测试可直接运行在不可调试的正式包上：

```sh
JAVA_HOME=/path/to/jdk21 GRADLE_USER_HOME=/path/to/gradle-home \
  ./android/gradlew -p android -PpaperisTestBuildType=release \
  :app:assembleRelease :app:assembleReleaseAndroidTest :app:testReleaseUnitTest :app:lintRelease
python3 tools/verify_reader.py --serial YOUR_EMULATOR_SERIAL --allow-reset-test-data \
  --build-type release --install --suite screen-behavior --suite responsive300 --suite responsive-window
```

`screen-behavior` 通过实际触摸切换刷新模式，对同页不同模式和连续翻页后的终帧逐像素比较；检查材质过渡、减少动态、前后台常亮释放、文件页恢复系统行为、真实显示旋转锁定与重新启动后的正文锚点。`upgrade-prepare` 在已发布 preview.17 上建立自有夹具，实际覆盖安装 release APK 后由 `upgrade` 核对原文件、阅读位置、书签、预设和签名。两阶段必须使用各自匹配的测试变体；不要清除应用数据。
