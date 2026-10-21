# Vin的提权工具 / DFRoot

一个面向 GKI 内核的 Android 提权工具，做成能装到手机上的 APK：

- 8 个 GKI KMI 的内核模块全部内嵌在 native 库里，**运行时按内核实际版本自动选择**，一个安装包通吃 `android12-5.10` 到 `android17-6.18`
- 一次点击完成：写入模块 → 触发加载 → 关闭 SELinux → 拉起 su 守护进程
- 支持**开机自启动**，重启后自动尝试提权，并把每一步写进可查看的日志
- 提供残留检测与清理（模块 / `/dev` 标记 / SELinux 状态）

> 历史原因包名仍是 `df.root`，native 库名仍是 `libdfroot.so`，界面与桌面显示名是「Vin的提权工具」。包名改动会导致必须卸载重装，所以保留。

---

## 它做了什么

```
App 进程
  └─ 执行 native 程序 libdfroot.so
       ├─ 读 uname()，解析出 GKI KMI，选出对应的内嵌 .ko
       ├─ 用 Dirty Pipe 在页面缓存里改写 crash_dump64
       ├─ 把选中的 .ko 写进 vendor 分区一个现有 .so 的页面缓存
       ├─ 把 shellcode 写进 libc++.so 里 sentry 构造函数的原位
       └─ 触发 sentry
            └─ shellcode 在 init 上下文里执行
                 └─ 以 vendor_modprobe 身份 insmod 那个被改写的 .so
                      └─ 内核模块 dfroot.ko
                           ├─ 清 crash_dump64 页缓存
                           ├─ selinux_state = permissive
                           └─ call_usermodehelper → bootstrap
                                └─ ksud late-load
                                     └─ su 守护进程上线
```

任何一个 patch 步骤失败，写入方都会把原字节写回去，不留半截状态。

---

## 支持的设备

| KMI | 内核版本 | 目标 Android |
|---|---|---|
| `android12-5.10` | 5.10 | 12 |
| `android13-5.10` | 5.10 | 13 |
| `android13-5.15` | 5.15 | 13 |
| `android14-5.15` | 5.15 | 14 |
| `android14-6.1` | 6.1 | 14 |
| `android15-6.6` | 6.6 | 15 |
| `android16-6.12` | 6.12 | 16 |
| `android17-6.18` | 6.18 | 17 |

前提条件：

- **GKI 内核**（`uname -r` 里带 `androidNN` 标记）。非 GKI 内核没有这个标记，工具会直接判定不支持并退出。
- 设备上**已安装 KernelSU 管理器**。bootstrap 会去管理器包里取 `libksud.so` 作为 su 守护进程；没有就只关 SELinux，不会给你 su。
- vendor 分区上存在下列任一文件（写模块用）：
  - `/vendor/lib64/libbinderdebug.so`
  - `/vendor/lib64/libstagefrighthw.so`
  - `/vendor/lib64/libstagefright_aidl_bufferpool2.so`

  都不存在的话需要自己往 `exp.c` 的 `detect_ko_target()` 里加候选。

开发时验证过的机器是小米 13（fuxi），Android 16 / SDK 36，内核 `5.15.178-android13-8-...`，命中 `android13-5.15`。

---

## 目录结构

```
lkm/
  dfroot.c              内核模块。版本无关：符号全部经 kallsyms 动态解析
  Makefile              单个 KMI 的模块构建

app/src/main/jni/
  exp.c                 利用主程序：页面缓存写入、libc++ 劫持、触发链路
  ko_select.c/.h        唯一版本相关的代码：内核识别 + 模块选择
  gen_kmi_inc.py        扫描 ko/ 目录，生成符号声明与选择表
  libcxx.S              注入的 shellcode，以及 patch 用的数据区
  bootstrap.c           模块加载后在 init 上下文里启动 ksud
  splicehelper.c        被换成 crash_dump64 的静态小程序
  elf_parser.c          定位 libc++.so 里的 sentry 构造函数
  aes256.h              AES-256，用于构造 Dirty Pipe 的 IV
  ko/                   每个 KMI 一个编译好的 .ko
  t_ko_select.c         选择逻辑的单元测试

app/src/main/java/df/root/
  MainActivity.java     主界面：内核信息、执行、清理、运行日志
  DisclaimerActivity.java 首次启动的免责声明（倒计时后才可同意）
  SettingsActivity.java 设置：自启动开关、模块列表、维护（日志）
  KernelInfo.java       内核识别（Java 侧，规则与 native 一致）
  ExploitRunner.java    启动 native 程序、收日志、检测 su、残留清理
  BootReceiver.java     开机广播接收
  AutorunService.java   前台服务，承载开机时的完整执行流程
  BootLog.java          开机链路文件日志
  BootLogViewer.java    日志查看弹窗（主界面与设置页共用）
  BuildInfo.java        版本信息拼装
```

---

## 版本识别：整个工程只有一处知道内核版本

`ko_select.c` 是唯一版本相关的地方。其余全部逻辑（页面缓存写入、crash_dump64 替换、
libc++ sentry 劫持、模块加载）都对版本不敏感——它们只是把选出来的字节打进一个文件。

选择规则：

1. **内核 `major.minor` 是硬过滤**。vermagic 由它构成，不匹配内核直接拒绝加载。
2. 同版本内优先精确匹配 android release。
3. 匹配不到就用第一个同版本模块作 fallback，并在日志与界面上明确标注 `fallback`。
   这个 fallback 大概率仍会失败（vermagic 里带着另一个 release 号），但比一句
   「不支持」有信息量。

### 新增一个内核版本

不需要改任何源码：

1. 用对应 KMI 的内核树编译 `lkm/dfroot.c`
2. 产物放进 `app/src/main/jni/ko/dfroot-<android>-<major>.<minor>.ko`
3. 重新构建

`gen_kmi_inc.py` 在构建时扫描目录生成表，native 与 Java 两侧都会自动认出新模块。

---

## 构建

App 与内核模块互相独立：模块需要一个能编 arm64 内核模块的容器镜像，App 需要 Android SDK。

### 内核模块

```sh
make lkm
```

每个 KMI 在对应的 DDK 镜像里编译，保证 vermagic 和结构体布局来自正确的内核树；
产物用 `llvm-objcopy --strip-unneeded` 去掉调试段，让它足够小——这个模块是要一页一页
写进 vendor 库的页面缓存里的。

镜像名从 `.github/workflows/build.yml` 的 `image:` 行读取（本仓库不附带这个 workflow，
需要自己准备任意能编 GKI 模块的 DDK 镜像，用 `DDK_IMAGE` / `DDK_RELEASE` 覆盖）。

### App

需要 Android SDK、NDK 27、JDK 17：

```sh
make apk          # 产出 out/DFRoot.apk
```

首次构建会在本地生成签名密钥 `keystore.jks`（口令见 `Makefile`）。**这个密钥不进仓库**，
自己留好——换了密钥就无法覆盖安装。

### 不用 Gradle 出包

`tools/build_native.sh` 与 `tools/build_apk.sh` 是开发时实际使用的构建路径：

```sh
sh tools/build_native.sh     # native，clang，target aarch64-linux-android24
bash tools/build_apk.sh      # APK，javac → d8 → aapt2 → zipalign → apksigner
```

不想用这两个脚本的话，装好 Android SDK/NDK 后 `make apk`（走 Gradle）产出的东西是一样的。

### 测试

```sh
make test          # native + java 两套
make test-native   # 只跑模块选择逻辑
make test-java     # 只跑 Java 侧解析逻辑
```

两套测试用同一组内核字符串做 fixtures，防止 native 和 Java 的实现慢慢跑偏。

---

## 使用

### 首次

1. 安装 APK，阅读免责声明，倒计时结束后点同意
2. 授予通知权限（前台服务与结果提醒需要）
3. 在设置里确认厂商后台限制已放开（小米：省电策略改「无限制」，允许自启动）
4. 回主界面点「开始执行」，保持屏幕常亮、App 在前台
5. 看到 `ksud start: SUCCESS` 即成功，之后在 KernelSU 管理器里授权本应用

### 开机自启动

在设置页打开「重启后自动执行」。开启后每次重启都会在后台跑一遍：

```
BOOT_COMPLETED → AutorunService（前台服务）
  → 执行 exploit
      ├─ 成功 → 轮询最多 20 秒等 su 可用 → 检测残留 → 有残留就清理
      └─ 失败 → 重试一次 → 仍然失败则关闭自启动并发通知提醒
```

失败一次会自动重试（漏洞本身比较稳），两次都失败才关闭自启动，避免每次开机反复
刷同一段坏代码。想重新开启，用设置页的「重置自启动锁定」。

### 日志

开机链路的每一步都写进 `/data/data/df.root/files/boot.log`（应用私有目录，重启不丢），
包括被忽略的广播、`startForeground` 的异常、exploit 的每一行输出。主界面和设置页都能
查看、复制、清空。这个文件是排查「开机没反应」的唯一可靠依据——logcat 在开机早期
经常拿不到。

### 清理

执行过一次之后，清理按钮才会变可用。它会做三件事：

- `rmmod dfroot` 卸载模块
- 删掉 `/dev/dfm*`、`/dev/dfme*`、`/dev/dfmw*` 标记
- `setenforce 1` 恢复 SELinux

清理走 `su`，所以需要 KernelSU 已授权本应用。

---

## 实现上的几个坑（值得知道）

这些都是踩过之后写进注释的：

**为什么开机自启动用 `specialUse` 而不是 `dataSync`。** `dataSync` 语义上最贴切
（一次有限时长的本地操作），但 Android 14+ 明确禁止 `dataSync` 类型的前台服务从
`BOOT_COMPLETED` 启动，会抛 `ForegroundServiceStartNotAllowedException`。`shortService`
允许从开机启动但有约三分钟上限，exploit 跑到一半就会被杀——正是这个服务想避免的情况。
`specialUse` 没有时长限制且允许开机启动。

**为什么自启动不能先检测 su。** 重启后没有 root 才正是这个功能存在的理由。早期版本
在开机时先 `suAvailable()` 再决定跑不跑，等于「先有 root 才能自启动」，必然全部跳过。
现在直接执行 exploit，跑完之后才等 su。

**为什么清理放在 exploit 跑完之后。** 开机那一刻没有 root，`detectResidue()` 只能返回
`UNKNOWN`，那次检测纯属浪费。正确顺序是跑完 → 等 su → 清理。

**为什么 receiver 在 manifest 里是 `enabled="true"`。** 有些 ROM 的包管理器只在下次
包扫描时才重新评估组件状态，manifest 里 disabled 的 receiver 即使运行时
`setComponentEnabledSetting()` 打开也收不到广播。保持启用、在 `onReceive` 里看偏好
决定是否执行，可以完全绕开这个问题。

**为什么「exploit 返回 0」不等于「已经有 su」。** 模块加载和 SELinux 下降在同一次
运行内完成，但响应 su 的守护进程是异步起来的，冷启动时这个间隙最大。所以要轮询。

**为什么 `/system/bin/su` 在重启后消失。** `/system` 是只读的 erofs，su 是 KernelSU
运行时挂上去的，重启即失效。看到它存在不代表开机那一刻也有。

---

## 风险

这是内核级提权工具，下面这些是真的会发生：

- **可能开不了机。** 如果 `libc++.so` 被改坏且还原失败，zygote 起不来。
- **可能卡死。** 失败有时表现为内核卡死（黑屏 / 无响应），长按电源键强制重启即可恢复。
  这一版不动任何分区、不刷 boot、不格式化数据，所以不会造成系统损坏或数据丢失。
- **可能丢数据。** 写的是页面缓存，掉电时落盘状态不确定。
- **不对所有机型成立。** vendor 候选路径、shellcode 偏移、sentry 定位都依赖具体环境，
  换机型很可能需要调整。

**不要在日常使用的设备上跑。不要在没有备份的设备上跑。**

---

## 已知不足

- exploit 有偶发失败（约每十几次一次），表现为 `timeout: ERROR - check logcat & dmesg`，
  之后自动还原 trampoline 与 shellcode。自启动带了重试一次兜底。
- 首次开机时若 su 授权弹窗来不及出现，exploit 仍会成功，但应用拿不到 su，
  需要在 app 内手动执行一次并授权。

---

## 致谢与来源

本工程的部分内容来自下面这些项目，感谢原作者：

- **[mitschud/DirtyFrag](https://github.com/mitschud/DirtyFrag)** — 本工具的部分实现思路与
  代码参考了该项目。如果原作者对这里的引用方式或使用有任何异议，请通过下方
  Issues 联系，收到后会立即删除相关内容。
- **[Odzhan 的 shellcode 工具集](https://github.com/odzhan/shellcode)** — `jni/include.inc`
  里的常量表（BSD 3-clause）。
- **[KernelSU](https://github.com/tiann/KernelSU)** — bootstrap 拉起的是 KernelSU 的
  `ksud late-load`，KernelSU 本身是 GPL-3.0，**不随本仓库分发**，需要用户自行安装。

---

## 许可

- `lkm/dfroot.c`：GPL-2.0（内核模块必须）
- 其余部分：MIT，见 `LICENSE`