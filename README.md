# Paperis 纸墨

**如纸般温润，让阅读回归纯粹。**

*As gentle as paper, as pure as reading.*

面向 Android 手机与 Pad 的极简阅读器，以细腻的黑白墨水屏质感，找回纸质书的宁静与沉浸。

**墨水屏质感 · 自动文本重排 · 离线 OCR · 本地书库**

![Android 13+](https://img.shields.io/badge/Android-13%2B-555555)
![Stable](https://img.shields.io/badge/Status-Stable-777777)
[![MIT License](https://img.shields.io/badge/License-MIT-555555)](LICENSE)

## 下载与安装

[![下载 Android APK](https://img.shields.io/badge/下载_Android_APK-1.0.1-303030?style=for-the-badge&logo=android&logoColor=white)](https://github.com/dpan538/Paperis/releases/download/v1.0.1/Paperis-1.0.1.apk)

**[直接下载 APK](https://github.com/dpan538/Paperis/releases/download/v1.0.1/Paperis-1.0.1.apk)** · [版本说明与历史下载](https://github.com/dpan538/Paperis/releases)

需要 **Android 13 及以上、ARM64 设备**。下载后按系统提示安装；已有官方预览版可直接覆盖升级，也可在应用内检查更新。

## 核心特性

| 功能 | 阅读体验 |
| --- | --- |
| **纸墨质感** | 白瓷、护眼、类纸三种材质，细腻纹理与自然墨迹过渡。 |
| **屏幕与刷新** | 自动、纸感、轻快三种刷新方式，支持阅读时常亮与方向锁定。 |
| **阅读预设** | 五档冷暖、三档对比度与亮度，支持夜间前光模拟和黑底白字的深色模式。 |
| **自由排版** | 调整字体、字号与行距，导入自定义字体；适配单栏与双栏，重排后自动复检并保留阅读位置。 |
| **章节导航** | 保留原书目录与标题层级，缺少有效目录时自动生成，兼顾中英文段落与换行。 |
| **离线识别** | 内置中英文离线 OCR，支持扫描 PDF、插图与原页查看。 |
| **本地书库** | 多选或按文件夹导入，重复文件自动跳过；支持单本重新排版与删除，无需重复导入。 |
| **保存与恢复** | 自动保存阅读进度与书签，支持备份到自选本地文件夹并恢复书库。 |

## 格式支持

| 格式 | 核心支持 |
| --- | --- |
| TXT | 多编码文本、段落重排、自动目录 |
| EPUB | 正文重排、分级目录、插图 |
| PDF | 文本重排、扫描件 OCR、插图与原页查看 |
| CBZ / 图片 ZIP | 漫画逐页阅读、自然页序排列 |
| Markdown / HTML | 正文、标题与链接文字 |
| DOCX | 段落、标题与自动目录 |

单文件最大 **64 MiB**；彩色插图与漫画以灰度呈现，原文件保留。暂不支持加密书籍、CBR / RAR、旧版 DOC、MOBI、AZW；复杂表格、公式与固定版式不保证原样还原。

## 手机与平板适配

Paperis 重点适配主流 Android 手机与小米平板，支持横竖屏、分屏、系统大字号和不同窗口尺寸。手机采用适合小屏的字号与单栏布局，大平板保留宽屏阅读效果；**小米 Pad 9 Pro Max** 继续作为主要平板适配目标。

当前正式版为 **1.0.1**，已通过手机与平板窗口的模拟器验证，仍需结合具体小米实机验收。墨水屏质感与前光均为视觉模拟。

## 隐私与许可

无需账号，书籍、OCR 与阅读记录均在本机处理和保存，无广告、无追踪，也不申请通知权限。

仅在主动更新或反馈时联网，提交的反馈会作为公开 GitHub Issue 发布；卸载前请备份到自选本地文件夹，以便重装后恢复。

项目采用 [MIT License](LICENSE)，第三方组件见 [许可说明](THIRD_PARTY_NOTICES.md)，导入的书籍与字体仍遵循各自授权。

---

[开发说明](DEV.md) · [构建与贡献](CONTRIBUTING.md) · [测试方案](docs/REFLOW_TESTING.md)
