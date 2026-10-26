# Paperis 纸墨

**如纸般温润，让阅读回归纯粹。**

*As gentle as paper, as pure as reading.*

Paperis 纸墨 是一款专为 Pad 与平板设备打造的极简水墨屏风质感阅读器。致力于重新找回传统纸质书带给人的宁静与沉浸感。

![Native Kotlin](https://img.shields.io/badge/Native-Kotlin-555555)
![Android 13+](https://img.shields.io/badge/Android-13%2B-555555)
![Source preview](https://img.shields.io/badge/Status-Source%20Preview-777777)
[![MIT License](https://img.shields.io/badge/License-MIT-555555)](LICENSE)

[阅读体验](#阅读体验) · [文件与导入](#文件与导入) · [构建与贡献](CONTRIBUTING.md) · [English](#about) · [MIT](LICENSE)

## 阅读体验

打开应用，整页即是安静的墨水屏阅读界面。正文、文件列表和省略号菜单共用同一层温润的黑白灰表面。

- **层次与质感**：微暖纸灰底材、哑光表面、细密颗粒与下沉墨层；柔和的受光随设备姿态轻微变化，可在菜单中关闭。
- **三种阅读预设**：标准强调清晰的灰阶与墨迹；护眼·柔和降低视觉对比；类纸增加纸纤维般的微结构与墨色沉积差异。护眼为视觉预设。
- **有节奏的刷新**：翻页保留轻微原位残影；切换预设、书籍或字体经过清除、墨迹沉积与稳定的过程。支持完整刷新，并尊重系统关闭动画的设置。
- **原生文本重排**：调整字体、字号、行距与横屏单双栏；竖屏自动单栏。重排后尽量保留当前正文位置。
- **中文排版**：严格标点换行、两端排版、段末孤字回收与跨栏段落保护，减少最后一行只有一个字的情况。
- **字体选择**：系统宋体、黑体，以及导入自己的 TTF、OTF、TTC 字体；缺失字形由系统回退。
- **轻量记录**：每本书保存阅读位置，提供目录、进度与简单书签。

点击左右区域、左右滑动或使用音量键翻页。右上角省略号和正文中央可打开阅读菜单；长按正文执行完整刷新。

Paperis 使用 Kotlin、原生 Canvas 与 AGSL 实现显示材质。软件模拟不改变平板屏幕的物理发光方式。

## 文件与导入

书由你带来。Paperis 提供简单的本地文件列表，支持系统文件选择器多选、按文件夹导入与目录浏览，也可从文件管理器打开或分享文档到应用。重复文件会跳过；无法解析的文件会给出原因。

| 格式 | 当前支持 |
| --- | --- |
| TXT | UTF-8、UTF-16、GB18030 编码识别与正文重排 |
| EPUB | 按书脊顺序提取正文与章节标题，重新排版 |
| Markdown / HTML | 提取可读段落与链接文字 |
| DOCX | 提取文字与段落 |
| PDF | Android 15 及以上提取文本层并重排 |

导入以可读正文为主，暂不保留复杂表格、原书图片布局、固定版式和网页交互。扫描 PDF、加密正文、旧版 DOC、MOBI、AZW 暂不支持；PDF 暂不提供 OCR。

单文件上限 32 MB，解析后正文上限 100 万字，单次文件夹导入最多 100 个文件；自定义字体上限 16 MB。应用保存本地正文副本，原文件保留在用户选择的位置，不修改原书。

## 平板适配与项目状态

- **系统**：Android 13 及以上。
- **主要适配目标**：小米 Pad 9 Pro Max，横屏 3408 × 2272、3:2 布局，并支持旋转后的竖屏阅读。
- **当前阶段**：可构建的原生源码预览。当前验证使用 Android 模拟器；尚未完成小米实机验证，也尚未发布正式 APK。

欢迎为其他 Android 平板提交适配分支与 PR。设备信息、构建步骤和验证要求见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 隐私

无需账号。书籍通过系统文件选择器授权导入，正文副本、导入字体、阅读位置和偏好保存在应用本地。清除应用数据或卸载会移除这些副本，不会删除原文件。

应用不申请网络权限或全盘存储权限，不包含广告、统计追踪或遥测。你导入的书籍和字体仍受各自的版权与许可约束。

## About

**Paperis 纸墨 — As gentle as paper, as pure as reading.**

A minimal native Android reader for tablets, with a warm monochrome e-paper-inspired surface, staged ink refresh, local document import, text reflow, custom fonts and quiet reading presets. Built with Kotlin, Canvas and AGSL, for Android 13 and later.

The primary layout target is the Xiaomi Pad 9 Pro Max. This is a source preview validated on an emulator; physical-device validation is still pending. See [CONTRIBUTING.md](CONTRIBUTING.md) for building and tablet adaptation.

## 许可证

项目原创代码、文档与原创示例使用 [MIT License](LICENSE)。Gradle、Kotlin 等第三方组件保留各自的许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。用户自行导入的书籍、字体与其他内容不属于项目的 MIT 授权范围。
