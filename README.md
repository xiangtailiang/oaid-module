# OAID Provider — LSPosed module

在 **LineageOS / AOSP** 等没有厂商 OAID 服务的 ROM 上,给 App 提供一个**合法的 OAID**。

国内的 OAID（匿名设备标识符）由各**手机厂商的系统级服务**提供，App 通过 MSA 统一 SDK 或直接 `bindService` 调用厂商 AIDL 服务来读取。刷了 LineageOS/AOSP 后这些服务被移除，App 拿不到 OAID（MSA SDK 返回 `1008612 不支持的设备厂商`，厂商 AIDL 绑定失败）。本模块在**框架层**拦截这些读取路径，统一返回一个固定的 OAID。

> A vendor-agnostic LSPosed/Xposed module that supplies a valid OAID to apps on ROMs without an OEM OAID service (LineageOS / AOSP). It hooks at the Android **framework layer**, so it works regardless of which vendor mechanism an app uses and survives R8 minification / class relocation.

---

## 工作原理

不针对某一个厂商 SDK，而是 hook 所有厂商 OAID 路径都会经过的**框架级入口**（用保留的 Intent/包名/URI 字符串匹配，因此抗混淆）：

| # | Hook 点 | 作用 |
|---|---------|------|
| 1 | `ApplicationPackageManager.getPackageInfo` | 把厂商 OAID 服务包伪装成"已安装",让各实现的 `supported()`/前置检查通过 |
| 2 | `ContextImpl.bindService` / `bindServiceAsUser` | 命中任意已知厂商 OAID 服务的 Intent 时,投递一个**假的 `Binder`** |
| 3 | 假 Binder 的 `queryLocalInterface` + `onTransact` | `queryLocalInterface` 按 descriptor 动态生成 AIDL 接口代理(所有 String getter 返回 OAID);接口被 relocate/无法按名加载时,回落到 `onTransact` 直接写回 OAID |
| 4 | `ContextImpl.unbindService` | 吞掉伪造连接的解绑,避免 `Service not registered` 崩溃 |
| 5 | `ContentResolver.query` / `call` | 通过 ContentProvider 暴露 OAID 的厂商(Meizu / Vivo / Nubia) |
| 6 | `com.bun.miitmdid.core.MdidSdkHelper.InitSdk` | MSA 统一 SDK 路径(构造 `IdSupplier` 代理并回调 `OnSupport`) |
| 7 | `java.lang.Class.forName` | Xiaomi/Redmi 反射路径:原查找失败且类名为 `com.android.id.impl.IdProviderImpl` 时,返回模块内置的同名类,反射即可取到 OAID |

### 覆盖范围

- **bindService AIDL（主路径,大多数 App）**：OPPO / OnePlus / realme（heytap `com.heytap.openid`）、OPPO stdid（`com.oplus.stdid` / `com.coloros.mcs`）、Samsung、ASUS、Lenovo/Moto/ZUI、Coolpad、Freeme、360/Qiku、Huawei OPENIDS service、MSA service（`com.mdid.msa`）。
- **ContentProvider**：Meizu、Vivo、Nubia。
- **MSA 统一 SDK**：`com.bun.miitmdid`。
- **Xiaomi/Redmi 反射**：`com.android.id.impl.IdProviderImpl#getOAID(Context)`(模块内置同名类 + hook `Class.forName`,在 AOSP 上也能用)。
- **不覆盖**：Huawei/Honor HMS（`AdvertisingIdClient`)——依赖只存在于华为/荣耀原厂系统上的 HMS 类,AOSP 上本就不存在,无需也无法伪造。

---

## 兼容性

- Android 8.1 ~ 15（已在 Android 15 验证）
- 需要 **Magisk + Zygisk** 且安装 **LSPosed**（或其后继 [JingMatrix/Vector](https://github.com/JingMatrix/Vector)）
- 已验证设备：OnePlus 6T（fajita）/ LineageOS 22（Android 15）

---

## 编译

无需 Gradle,只用 Android SDK 的命令行工具(`aapt2` / `d8` / `zipalign` / `apksigner`)+ JDK 11+。

```bash
# 需要：Android SDK（build-tools + 某个 platform 的 android.jar）、JDK 11+
export ANDROID_SDK_ROOT=$HOME/Library/Android/sdk   # 按需调整
cd module
./build.sh            # 产出 module/oaid-provider.apk

# 可选：用自定义 OAID 值编译
OAID=12345678-90ab-cdef-1234-567890abcdef ./build.sh
```

`build.sh` 流程:javac 编译(编译期只引用 `stubs/` 里的 Xposed 桩,不打进 APK)→ `d8` 转 dex → `aapt2` 打包 → `zipalign` → `apksigner`。

> **关于签名:无需自带 keystore。** 首次编译会自动生成一个调试 keystore(`module/debug.keystore`,已 gitignore),之后复用同一个 key,所以重复编译后 `adb install -r` 仍可覆盖更新。
> 注意:自己编译出来的 APK 与本仓库 Release 里的 APK 是**不同签名**的,二者不能互相覆盖安装——切换时先 `adb uninstall com.oaidfix`。也可用 `KEYSTORE=/path/to/your.keystore ./build.sh` 指定自己的 key。

---

## 安装与启用

```bash
adb install -r module/oaid-provider.apk
```

然后在 **LSPosed/Vector 管理器**里:

1. 「模块」→ 启用 **OAID Provider**
2. 选择**作用域**:勾选你要提供 OAID 的目标 App
3. 重启目标 App

> Vector(LSPosed 后继)的管理器是寄生式的,无桌面图标。可用
> `adb shell 'am start -c "org.lsposed.manager.LAUNCH_MANAGER" "com.android.shell/.BugreportWarningActivity"'`
> 打开,或在 Magisk 里点该模块的「操作」。

---

## 修改返回的 OAID

默认 OAID 是一个固定的 UUID。改 [`module/src/com/oaidfix/OaidModule.java`](module/src/com/oaidfix/OaidModule.java) 里的 `OAID` 常量后重新编译,或编译时用 `OAID=... ./build.sh` 注入。

---

## 验证

`testapp/` 是一个自带的 OAID 自测 App(内置模拟的 MSA SDK)。也可以用
[`gzu-liyujiang/Android_CN_OAID`](https://github.com/gzu-liyujiang/Android_CN_OAID) 的 `demo.apk`:把它加入模块作用域后,点「获取手机厂商专有的广告标识符」即可看到返回的 OAID。

模块运行日志(logcat):

```
LSPosed-Bridge: [OAID] faked vendor OAID service ComponentInfo{com.heytap.openid/com.heytap.openid.IdentifyService}
```

---

## 免责声明

本项目用于在缺失厂商 OAID 服务的自编译 ROM 上进行**开发与测试**。请遵守相关法律法规与各平台条款,自行承担使用风险。

## 致谢

读取路径的枚举参考了 [gzu-liyujiang/Android_CN_OAID](https://github.com/gzu-liyujiang/Android_CN_OAID)。
基于 [LSPosed](https://github.com/JingMatrix/Vector) Xposed 框架。
