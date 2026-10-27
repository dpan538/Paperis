# Paperis 纸墨

**如纸般温润，让阅读回归纯粹。**

*As gentle as paper, as pure as reading.*

Paperis 纸墨 是一款专为 Pad 与平板设备打造的极简水墨屏风质感阅读器。致力于重新找回传统纸质书带给人的宁静与沉浸感。

## 下载

[![下载 Android APK](https://img.shields.io/badge/下载_Android_APK-0.1.0--preview.14-303030?style=for-the-badge&logo=android&logoColor=white)](https://github.com/dpan538/Paperis/releases/download/v0.1.0-preview.14/Paperis-0.1.0-preview.14.apk)

**[直接下载 Paperis 纸墨 APK](https://github.com/dpan538/Paperis/releases/download/v0.1.0-preview.14/Paperis-0.1.0-preview.14.apk)** · [查看版本与校验值](https://github.com/dpan538/Paperis/releases)

Android 13+，面向平板的预览版本，使用项目的预览开发签名。下载后允许浏览器安装应用；已有同签名预览可覆盖升级。小米 Pad 9 Pro Max 的实机验证仍待完成。

![Native Kotlin](https://img.shields.io/badge/Native-Kotlin-555555)
![Android 13+](https://img.shields.io/badge/Android-13%2B-555555)
![Source preview](https://img.shields.io/badge/Status-Source%20Preview-777777)
[![MIT License](https://img.shields.io/badge/License-MIT-555555)](LICENSE)

[阅读体验](#阅读体验) · [文件与导入](#文件与导入) · [构建与贡献](CONTRIBUTING.md) · [重排验证](docs/REFLOW_TESTING.md) · [English](#about) · [MIT](LICENSE)

## 阅读体验

打开应用，整页即是安静的墨水屏阅读界面。正文、文件列表和省略号菜单共用同一层温润的黑白灰表面。新安装默认使用白瓷，夜间前光与深色模式关闭；已有模式选择会保留。

- **层次与质感**：微暖纸灰底材、哑光表面、细密颗粒与下沉墨层；柔和的受光随设备姿态轻微变化，可在菜单中关闭。
- **三种阅读预设**：白瓷呈现细润致密的浅色表面与清晰墨迹；护眼降低视觉对比、保留轻微暖灰漫散射；类纸增加交错纤维般的微结构与墨色沉积差异。护眼为视觉预设。
- **有节奏的刷新**：翻页采用短暂的墨迹转换，完成后只保留当前页，不累积旧字或定期强制黑闪；切换预设、书籍或字体经过清除、墨迹沉积与稳定的过程。支持手动完整刷新，并尊重系统关闭动画的设置。
- **章节与目录**：读取 EPUB 2 NCX / EPUB 3 导航与原文锚点，保留层级；缺少有效目录时离线识别正文标题，明确标注“自动识别目录”。目录可以滚动，点击跳转到真实正文位置。
- **原生文本重排**：调整字体、字号、行距与横屏单双栏；竖屏自动单栏。用正文字符位置保留阅读进度，连续放大、缩小或旋转时跟随同一段文字。
- **中文排版**：严格标点换行、两端排版、段末孤字回收与跨栏段落保护；标题分级、主章起页，避免把标题单独留在栏尾，并为悬挂标点保留光学余量。页眉显示当前章节。
- **字体选择**：系统宋体、黑体，以及导入自己的 TTF、OTF、TTC 字体；缺失字形由系统回退。
- **夜间与深色**：夜间模式模拟光导层充分混光后的均匀柔和前光，点亮后纸面变亮，三档亮度依次增强；深色模式提供黑底白字，两者独立开关。灯光为视觉模拟。
- **设置与更新**：与阅读菜单相同大小的设置页，支持中文 / English、手动拉取更新与提交反馈。更新在应用内下载并保留进度，核对文件哈希、包名、版本与签名后打开 Android 系统安装确认；首次需要允许 Paperis 安装应用。反馈可在应用内填写，返回或提交失败会保留草稿，核验公开回执后才清空。
- **轻量记录**：每本书保存阅读位置，提供目录、进度与简单书签。

点击左右区域、左右滑动或使用音量键翻页。右上角省略号和正文中央可打开阅读菜单；“＋ 导入书籍”固定在主菜单一级；文件窗口顶部另有“＋ 导入文件”，可直接打开系统文件选择器。长按正文执行完整刷新。

Paperis 使用 Kotlin、原生 Canvas 与 AGSL 实现显示材质。软件模拟不改变平板屏幕的物理发光方式。

## 文件与导入

书由你带来。Paperis 提供简单的本地文件列表，支持系统文件选择器多选、按文件夹导入与目录浏览，也可从文件管理器打开或分享文档到应用。导入时显示当前文件和整理进度，相同文件会跳过；整理任务与已完成 PDF 页面会保存进度，可在菜单中暂停；离开应用会保存进度，返回后继续。无法解析的文件会给出原因。

| 格式 | 当前支持 |
| --- | --- |
| TXT | UTF-8、UTF-16、GB18030 编码识别与正文重排 |
| EPUB | 按书脊顺序保留段落、标题、NCX / EPUB 3 目录和本地位图插图，重新排版 |
| CBZ / 图片 ZIP | 按文件名中的自然数字顺序逐页阅读；每张图片单独占一页，横屏也不拆成双栏 |
| Markdown / HTML | 提取可读段落、标题层级与链接文字 |
| DOCX | 提取段落和标题样式，生成目录 |
| PDF | Android 15+ 优先提取文本层；扫描页及 Android 13/14 使用随包离线 OCR，支持简体、繁体、英文，恢复段落与目录，并提取灰度插图 |

彩色 EPUB、CBZ/图片 ZIP 漫画与 PDF 插图在阅读时转换为中性 16 阶灰度，以保留明暗层次并适配单色墨水屏；导入原文件和原始插图会继续以原始格式保存在本机与备份中。漫画按 `page2`、`page10` 这样的自然数字顺序整理，一张漫画图对应一个阅读页。PDF 使用页面渲染与文字区域分析提取位图和矢量插图；“阅读排版 → 重新排版本书 → 查看 PDF 原页”保留完整原页供核对。复杂表格、公式、竖排、完整 CSS 和固定版式不会被精确重建。加密正文、CBR/RAR、旧版 DOC、MOBI、AZW 暂不支持。

自动目录来自原书标题和正文线索，明确区分原始目录与自动识别目录。扫描 PDF 的 OCR 在本机运行，不需要下载模型或上传页面；采用精度优先模型自动识别简繁中文和英文，并保留原文字形与词间空格。模糊扫描、小字、复杂分栏仍可能错字或错序，请结合原页核对。

**旧书无需重复导入**：打开旧书时自动检查排版版本；也可进入“阅读排版 → 重新排版本书”，用已保存原文件重建段落、目录和插图，并保留阅读位置与书签。较早版本仅保存文字的书可以直接整理现有文字；若需要恢复原书目录和插图，在同一菜单选择“补充原文件”，选择原来的文件即可修复原书，不会另建副本。未保存的原页与图片需要原文件才能恢复。

单文件上限 **64 MiB**（大于 50 MB），解析后正文上限 200 万字符，单次导入最多 100 个文件；自定义字体上限 16 MiB。原文件与图片流式落盘，逐页处理 PDF，完整副本、正文、插图和目录存入应用文件目录，不依赖缓存，也不修改用户原书。

**长期保存与独立备份**：在“设置 → 保存与备份”选择自己的本地文件夹。导入、重新排版后自动写入并读回校验 `.paperis` 备份，也可手动备份全部书籍、从所选文件夹恢复原文件、正文、目录、插图、阅读位置和书签。每次保存新快照，完成校验后才标记为可恢复备份。选择本机文件夹时，这些独立文件可在卸载后保留；重装后重新授权该文件夹即可恢复。应用内副本会随卸载或清除应用数据移除；文件夹备份不会自动删除。

## 平板适配与项目状态

- **系统**：Android 13 及以上，当前预览 APK 为 ARM64。
- **主要适配目标**：小米 Pad 9 Pro Max。按实际窗口、显示密度和系统安全边距排版，正文跟随系统 SP 字体缩放；宽横屏双栏、窄窗口与竖屏单栏，短窗口菜单可以滚动。3408 × 2272、3:2 为项目参考配置，并非实机测量值。
- **当前阶段**：可构建的原生源码预览。当前验证使用 Android 模拟器；已提供预览 APK，尚未完成小米实机验证。

欢迎为其他 Android 平板提交适配分支与 PR。设备信息、构建步骤和验证要求见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 隐私

无需账号。书籍通过系统文件选择器授权导入，原文件的应用内副本、重排正文与插图、目录数据、导入字体、阅读位置和偏好保存在应用本地。清除应用数据或卸载会移除应用内副本，不会删除原文件或用户所选文件夹中的独立备份。OCR 也完全在本机处理。

应用不申请通知或全盘存储权限，不运行通知服务，不包含广告、统计追踪或遥测。点击“拉取更新”会连接 GitHub 公共版本接口，点击下载后将安装包保存在应用本机，安装由 Android 系统确认。点击“提交反馈”会将填写的内容、应用版本和重试标识发送至 Paperis 反馈服务，并在公开的 GitHub Issue 中核验回执；查看回执会打开浏览器。密钥仅保留在服务端；书库、字体和设备信息不会随反馈自动上传，也不会自动提交反馈。你导入的书籍和字体仍受各自的版权与许可约束。

## About

**Paperis 纸墨 — As gentle as paper, as pure as reading.**

A minimal native Android reader for tablets, with a warm monochrome e-paper-inspired surface, staged ink refresh with clean settled pages, adaptive window/inset layout, local document import, semantic paragraphs and headings, source or generated contents, grayscale EPUB/PDF illustrations, bundled offline Chinese/English OCR, notification-free in-app import tasks, manual reprocessing of saved books, 64 MiB imports, verified user-folder backup/restore, text reflow, custom fonts, Porcelain / Gentle / Paper surfaces, independent night lighting and dark mode, and draft-preserving in-app feedback. Built with Kotlin, Canvas and AGSL, for Android 13 and later.

The primary layout target is the Xiaomi Pad 9 Pro Max. This is a source preview validated on an emulator; physical-device validation is still pending. See [CONTRIBUTING.md](CONTRIBUTING.md) for building and tablet adaptation.

## 许可证

项目原创代码、文档与原创示例使用 [MIT License](LICENSE)。Gradle、Kotlin 等第三方组件保留各自的许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。用户自行导入的书籍、字体与其他内容不属于项目的 MIT 授权范围。
