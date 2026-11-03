# ADR-0014 辅导验算用 Symja 3.2.0

- 状态：已接受
- 日期：2026-11-03

## 背景

A4 要求数值、化简、因式分解和方程的结果来自计算工具，而不是模型自己报的数字。M2 只接了算术烟测和一个反射式桥，`matheclipse` 不在 APK 里。这是 `BLOCK-CAS-1`，`1.0.0` 之前必须做完。

## 决定

依赖 `org.matheclipse:matheclipse-core:3.2.0`（带上它声明的 `matheclipse-parser` 与 `matheclipse-external`）。`CalcEngines.preferred()` 返回 `SymjaCalcEngine`，实现既有的 `CalcEngine`，并增加 `AlgebraCas`（化简、因式、等价、一元方程解集）。算术烟测引擎留着，给显式传入的单测。

许可证：这三件 Maven 模块自 Symja 3.1.1 起是 **LGPL-3.0**（更早的文档写成 GPL-3）。LGPL-3.0 可以并进 GPL-3.0 程序；库本身不改，源码在 Symja 标签 `v3.2.0`。`matheclipse-external` 里还捆了 JAS（原项目 GPL 或 LGPL，作者把字节码另授 Apache-2.0 以便用于 Android）、Cream（LGPL-2.1 或更新）、SnuggleTeX（BSD）、Tablesaw 片段（Apache-2.0）。Guava 改用 `33.6.0-android`，不用 `-jre`。

运行：类文件是 Java 11 字节码，现有 Java 17 工具链可以编。`minSdk` 仍是 26。`F` 的静态初始化会调用 `AndroidLoggerFix`。绘图类引用 `java.awt`，验算路径不加载，R8 用 `-dontwarn`。首次加载放在后台单线程；主线程直接返回工具不可用。输入先过长度、嵌套和语法白名单，再给引擎，单次 2 秒。

## 后果

APK 变大。未压缩的传递依赖大约几十 MB。对照 main `156b68c`，release（R8，arm64-v8a）从 25,795,147 增到 33,550,633 字节（+7.4 MiB），dex 从 1 个增到 2 个，方法数从 46,006 增到 115,444。debug 增量约 +16.7 MiB。数字记在 `1.0.0-rc.1` 的 changelog。`minSdk` 26，不需要 multidex 支持库。换库只改 `AlgebraCas` 的实现。
