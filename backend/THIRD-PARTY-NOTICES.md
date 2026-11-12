# 后端第三方许可证声明（NekoMusic backend）

本目录 `backend` 的检索索引代码由 `ArchoeraMusic/app/core/indexer`（C#）移植而来，
自研代码随本软件以 **AGPL-3.0-or-later** 授权；下列第三方组件与数据按各自许可使用。

## Maven 依赖

| 组件 | 版本 | 许可证 | 说明 |
|---|---|---|---|
| `org.xerial:sqlite-jdbc` | 3.50.3.0 | **Apache-2.0** | 进程内 SQLite 驱动，自带含 FTS5 的原生库；用于构建 / 查询 `search.db` |

SQLite 本体为 **public domain**，FTS5 亦属之。

## 移植代码

`src/main/java/com/neko/music/search/` 下由 C# 移植的文件均保留文件头：

```
SPDX-License-Identifier: AGPL-3.0-or-later
Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2
```

ArchoeraMusic 与 NekoMusic 同为 AGPL-3.0（or-later），合并、翻译、修改无需额外授权；
分发时须继续以 AGPL-3.0 提供完整对应源码，并保留本说明。

## 内嵌数据表（`src/main/resources/search/`）

| 表 | 来源 | 许可证 | 说明 |
|---|---|---|---|
| `nfkc.tsv` | Unicode NFKC（`unicodedata`） | **Unicode License** | 逐码点兼容折叠 |
| `accent_fold.tsv` | Unicode NFD 规范分解（派生） | **Unicode License** | 拉丁去音符号折叠 |
| `variant_fold.tsv` | Unihan `kSimplifiedVariant` / `kJapaneseOldVariant` | **Unicode License** | 异体字 → 简体补齐 |
| `def_en2hanzi.tsv` / `def_hanzi2en.tsv` | Unihan `kDefinition` | **Unicode License** | 跨语言释义候选（查询侧） |
| `ja_readings.tsv` | Unihan `kJapanese` | **Unicode License** | 日文音读 / 训读 |
| `kana_romaji.tsv` | 本项目自研 | AGPL-3.0-or-later | 假名 → 罗马字 |
| `pinyin_words.tsv` / `pinyin_chars.tsv` / `pinyin_syllables.txt` | 由 **ToolGood.Words 3.1.0.3** 词库离线导出 | **Apache-2.0**（数据）；导出脚本与生成结果随本项目分发 | 词级多音字消歧、单字默认读音、拼音音节表 |

### Apache-2.0（ToolGood.Words 词库导出）

```
Apache License, Version 2.0
http://www.apache.org/licenses/LICENSE-2.0

Copyright 2017-2025 ToolGood
```

完整文本见 ToolGood.Words 仓库 `LICENSE`
（<https://github.com/toolgood/ToolGood.Words>）。
该词库按 Apache-2.0「原样（AS IS）」提供，不附带任何明示或默示担保；
拼音结果由上游词库决定，本项目不对准确性作保证。

### Unicode License（Unicode 数据）

```
Copyright © 1991-2024 Unicode, Inc. All rights reserved.
Distributed under the Terms of Use in https://www.unicode.org/copyright.html
```

## 合规说明（非法律意见）

以上组件与数据均为 AGPL-3.0 兼容的宽松 / 数据许可，不属于 GPL-2.0-only、SSPL、BSL
等禁止引入的许可。正式依据以上游官方许可文本为准。
