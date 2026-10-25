[English](README.md) · [Русский](README.ru.md) · **中文**

# Divan

一款轻量、无广告的 Android TV / Google TV 桌面，专为 Chromecast with Google TV（HD）这类性能较弱的设备设计。无需账号，无追踪，无内购。

## 功能

- **一行应用**，以横幅显示：长按确定键即可左移/右移、移到最前/最后、隐藏、更换横幅、卸载。
- **自定义横幅**：把 `<包名>.png` 放进电视上的 `Pictures/Divan/icons`；`<包名>.en.png`、`<包名>.ru.png`、`<包名>.zh.png` 会随界面语言切换。[`banners/`](banners) 里有一套 Apple SF Symbols 风格的现成横幅。
- **应用下方的内容行**：“继续观看”以及应用发布的频道（YouTube、IPTV、影视应用），可按应用、按频道开关。海报会缓存并预加载，滚动流畅。
- **天气**显示在角落（Open-Meteo，无需密钥）：依次通过 Wi-Fi 定位、IP、时区确定城市，并保持稳定——精度较低的定位不会覆盖精确定位得到的城市。
- **遥控器按键重映射**（无障碍服务）：单击、双击、长按；打开应用或链接、主页、返回、音量、媒体键、电源菜单等；可作用于所有应用、仅主屏幕或主屏幕以外。
- **电源**：开机自动打开指定应用，以及“空闲时不休眠”开关。
- **通知**：主屏幕上的铃铛和通知列表。
- **壁纸**来自 `Pictures/Wallpapers`，两种主题，应用行高度可调。
- **三种语言**：英语、俄语、简体中文——跟随系统，或在“外观 → 语言”中设置。

为弱硬件而做：扁平半透明面板，无模糊、无阴影，只有一个全屏图层；帧时间在 Chromecast HD 上实测。

## 要求

- Android TV / Google TV，Android 12（API 31）或更高。
- 无需 root。安装时需要用一次 `adb`。

## 构建与安装

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant com.rdsinteractive.divan android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.rdsinteractive.divan android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.rdsinteractive.divan android.permission.READ_TV_LISTINGS
# 预先编译：否则在系统自行编译之前，滚动会明显变慢
adb shell cmd package compile -m speed -f com.rdsinteractive.divan
```

然后按遥控器的主页键并选择 Divan，或将其设为默认桌面：

```bash
adb shell cmd package set-home-activity com.rdsinteractive.divan/.MainActivity
```

在 Google TV 上，原生桌面可能会重新接管主页键。是否停用它（`pm disable-user com.google.android.apps.tv.launcherx`）由你决定，可用 `pm enable` 恢复。

可选：在 Android 设置中开启无障碍服务“Divan：遥控器按键”以使用重映射，开启通知使用权以显示铃铛。

> 不要对 Divan 执行 `am force-stop`：Android 会因此关闭它的无障碍服务，需要手动重新开启。

## 横幅

```bash
python3 tools/make_banners.py out zh   # macOS：SF Pro / Hiragino 字体和 rsvg-convert（brew install librsvg）
adb push out/. /sdcard/Pictures/Divan/icons/
```

## 隐私

Divan 只在获取天气（Open-Meteo）、城市名称（Android 地理编码、ipwho.is）和内容行海报时联网。其他数据都不会离开电视。

## 许可证

[MIT](LICENSE)
