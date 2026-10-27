# Paperis 纸墨

**如纸般温润，让阅读回归纯粹。**

*As gentle as paper, as pure as reading.*

Paperis 纸墨 是一款专为 Pad 与平板设备打造的极简水墨屏风质感阅读器。致力于重新找回传统纸质书带给人的宁静与沉浸感。

## 下载

[![下载 Android APK](https://img.shields.io/badge/下载_Android_APK-0.1.0--preview.11-303030?style=for-the-badge&logo=android&logoColor=white)](https://github.com/dpan538/Paperis/releases/download/v0.1.0-preview.11/Paperis-0.1.0-preview.11.apk)

**[直接下载 Paperis 纸墨 APK](https://github.com/dpan538/Paperis/releases/download/v0.1.0-preview.11/Paperis-0.1.0-preview.11.apk)** · [查看版本与校验值](https://github.com/dpan538/Paperis/releases)

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
- **原生文本重排**：调整字体、字号、行距与横屏单双栏；竖屏自动单栏。重排后尽量保留当前正文位置。
- **中文排版**：严格标点换行、两端排版、段末孤字回收与跨栏段落保护；标题分级、主章起页，避免把标题单独留在栏尾，并为悬挂标点保留光学余量。页眉显示当前章节。
- **字体选择**：系统宋体、黑体，以及导入自己的 TTF、OTF、TTC 字体；缺失字形由系统回退。
- **夜间与深色**：夜间模式模拟光导层充分混光后的均匀柔和前光，点亮后纸面变亮，三档亮度依次增强；深色模式提供黑底白字，两者独立开关。灯光为视觉模拟。
- **设置**：与阅读菜单相同大小的设置页，支持中文 / English、手动拉取更新与提交反馈。反馈可在应用内填写，返回或提交失败会保留草稿；核验公开回执后才清空。更新下载与查看回执打开浏览器。
- **轻量记录**：每本书保存阅读位置，提供目录、进度与简单书签。

点击左右区域、左右滑动或使用音量键翻页。右上角省略号和正文中央可打开阅读菜单；“＋ 导入书籍”固定在主菜单一级；文件窗口顶部另有“＋ 导入文件”，可直接打开系统文件选择器。长按正文执行完整刷新。

Paperis 使用 Kotlin、原生 Canvas 与 AGSL 实现显示材质。软件模拟不改变平板屏幕的物理发光方式。

## 文件与导入

书由你带来。Paperis 提供简单的本地文件列表，支持系统文件选择器多选、按文件夹导入与目录浏览，也可从文件管理器打开或分享文档到应用。导入时显示当前文件和整理进度，完成后显示目录章节数；相同文件会跳过，旧版扁平导入的文件再次选择时会升级结构。无法解析的文件会给出原因。

| 格式 | 当前支持 |
| --- | --- |
| TXT | UTF-8、UTF-16、GB18030 编码识别与正文重排 |
| EPUB | 按书脊顺序保留段落、标题、NCX / EPUB 3 目录和本地位图插图，重新排版 |
| Markdown / HTML | 提取可读段落、标题层级与链接文字 |
| DOCX | 提取段落和标题样式，生成目录 |
| PDF | Android 15 及以上提取文本层，结合行坐标恢复段落、识别标题与页眉页码，并建立可跳转目录 |

EPUB 插图按阅读栏宽缩放，并以灰度原生绘制；保留内容和阅读顺序，不复制原书的固定图片位置。PDF 当前提取文字，不保留插图。复杂表格、公式、竖排、完整 CSS、固定版式和网页交互暂不保留。扫描 PDF、加密正文、旧版 DOC、MOBI、AZW 暂不支持；PDF 暂不提供 OCR。

自动目录来自原书标题和可核对的正文线索，不能保证覆盖没有任何章节标识的文档。这里的扫描是离线分析文本结构；纯扫描图片仍需 OCR，当前尚未提供。

单文件上限 32 MB，解析后正文上限 100 万字，单次文件夹导入最多 100 个文件；自定义字体上限 16 MB。应用保存完整的本地导入副本、重排正文和结构数据，原文件保留在用户选择的位置，不修改原书。旧版已导入书籍需要重新选择原文件，才能获得新版段落与目录。

## 平板适配与项目状态

- **系统**：Android 13 及以上。
- **主要适配目标**：小米 Pad 9 Pro Max。按实际窗口、显示密度和系统安全边距排版，正文跟随系统 SP 字体缩放；宽横屏双栏、窄窗口与竖屏单栏，短窗口菜单可以滚动。3408 × 2272、3:2 为项目参考配置，并非实机测量值。
- **当前阶段**：可构建的原生源码预览。当前验证使用 Android 模拟器；已提供预览 APK，尚未完成小米实机验证。

欢迎为其他 Android 平板提交适配分支与 PR。设备信息、构建步骤和验证要求见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 隐私

无需账号。书籍通过系统文件选择器授权导入，原文件的应用内副本、重排正文与插图、目录数据、导入字体、阅读位置和偏好保存在应用本地。清除应用数据或卸载会移除这些副本，不会删除原文件。

应用不申请全盘存储权限，不包含广告、统计追踪或遥测。点击“拉取更新”会连接 GitHub 公共版本接口；点击“提交反馈”会将填写的内容、应用版本和重试标识发送至 Paperis 反馈服务，并在公开的 GitHub Issue 中核验回执。密钥仅保留在服务端；书库、字体和设备信息不会随反馈自动上传。下载和查看回执通过浏览器打开，不会自动安装或提交反馈。你导入的书籍和字体仍受各自的版权与许可约束。

## About

**Paperis 纸墨 — As gentle as paper, as pure as reading.**

A minimal native Android reader for tablets, with a warm monochrome e-paper-inspired surface, staged ink refresh with clean settled pages, adaptive window/inset layout, local document import, semantic paragraphs and headings, source or generated contents, grayscale EPUB illustrations, text reflow, custom fonts, Porcelain / Gentle / Paper surfaces, independent night lighting and dark mode, and draft-preserving in-app feedback. Built with Kotlin, Canvas and AGSL, for Android 13 and later.

The primary layout target is the Xiaomi Pad 9 Pro Max. This is a source preview validated on an emulator; physical-device validation is still pending. See [CONTRIBUTING.md](CONTRIBUTING.md) for building and tablet adaptation.

## 许可证

项目原创代码、文档与原创示例使用 [MIT License](LICENSE)。Gradle、Kotlin 等第三方组件保留各自的许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。用户自行导入的书籍、字体与其他内容不属于项目的 MIT 授权范围。
