# Paperis 纸墨

**如纸般温润，让阅读回归纯粹。**

*As gentle as paper, as pure as reading.*

## 产品定位

**用你已有的平板，复刻一台墨水屏的阅读习惯；把 PDF / EPUB 做成真正的书，而不是把页面文字倒进一列。**

Paperis 不替代墨水屏硬件，也不宣称零功耗或真实电泳粒子。它把稳定的纸面、安静的黑白阅读和固定的翻页节奏带到平板上，并用本地重排、目录与离线 OCR 把 PDF 做成可读的书。

优先适配 **Xiaomi Pad 9 Pro Max**：13.3 英寸 3.4K LCD（3408 × 2272，308 PPI，144 Hz，700 nits 典型亮度），以 3408 × 2272 作为渲染与验收基准。

![Android 13+](https://img.shields.io/badge/Android-13%2B-555555)
![Stable](https://img.shields.io/badge/Status-1.0.5-303030)
[![MIT License](https://img.shields.io/badge/License-MIT-555555)](LICENSE)

## 下载与安装

[![下载 Android APK](https://img.shields.io/badge/下载_1.0.5_APK-303030?style=for-the-badge&logo=android&logoColor=white)](https://github.com/dpan538/Paperis/releases/download/v1.0.5/Paperis-1.0.5.apk)

**[直接下载 1.0.5 APK](https://github.com/dpan538/Paperis/releases/download/v1.0.5/Paperis-1.0.5.apk)** · [版本与历史下载](https://github.com/dpan538/Paperis/releases)

需要 **Android 13 及以上、ARM64 设备**。已有官方版本可直接覆盖升级，也可在应用内检查更新。

## 核心体验

- **纸墨质感**：白瓷、护眼、类纸三种纸面；环境光纸面；220 ms 无闪局部翻页。
- **PDF 成书**：PDFBox 读取真实页树、原生目录与页码标签；封面/整页插画作为一页等比缩放；扫描件离线 OCR。
- **安静阅读**：专注模式、夜间/深色/专注三个模式、章节导航、书签与本地阅读进度。
- **本地书库**：多选/文件夹导入、续读、重新排版、删除与本地备份恢复。

## 格式支持

| 格式 | 支持 |
| --- | --- |
| TXT | 多编码、段落重排、自动目录 |
| EPUB | 正文重排、分级目录、插图 |
| PDF | 文本重排、原生目录、整页图版、扫描件 OCR、原页查看 |
| CBZ / 图片 ZIP | 漫画逐页阅读、自然页序 |
| Markdown / HTML | 正文、标题与链接文字 |
| DOCX | 段落、标题与自动目录 |

单文件最大 **64 MiB**；彩色内容以灰度呈现，原文件保留。暂不支持加密书籍、CBR/RAR、旧版 DOC、MOBI、AZW；复杂表格、公式与固定版式不保证原样还原。

## 设备与说明

适配主流 Android 手机与平板，支持横竖屏、分屏和系统大字号。**Xiaomi Pad 9 Pro Max** 是主要平板目标；当前验证基于模拟器，仍需结合具体小米实机验收。墨水屏质感、环境光与前光均为视觉模拟。

## 隐私与许可

无需账号；书籍、OCR 与阅读记录均在本机处理和保存，无广告、无追踪，不申请通知权限。仅在主动更新或反馈时联网。

项目采用 [MIT License](LICENSE)；第三方组件见 [许可说明](THIRD_PARTY_NOTICES.md)，导入的书籍与字体仍遵循各自授权。

---

[开发说明](DEV.md) · [构建与贡献](CONTRIBUTING.md) · [测试说明](docs/REFLOW_TESTING.md)
