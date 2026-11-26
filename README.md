# 音频可视化 · WaveScope

Android 原生音频示波器 / 频谱可视化工具，使用 Java + Canvas 实现。

## 功能

- 导入音频或视频；视频自动提取音轨，忽略画面
- 麦克风监听
- Android 10+ 媒体音频捕获（MediaProjection）
- Y-T 时域示波器
- X-Y 李萨如相图（纯黑直角坐标、余辉、像素点阵、镜像、XY 交换）
- FFT 频谱与瀑布图
- 全文件频谱 / 实时瀑布切换，可拖动全文件瀑布光标定位
- 图传声：频谱 PNG 经逆 STFT 生成 WAV
- 导出 CSV、PNG、瀑布大图
- 全文件瀑布图时间轴细化到 0.1s/0.01s 级
- 可调目标帧率、FFT 预渲染缓存、波形测量、FPS 显示

## 构建

本项目没有 Gradle/AndroidX，使用原生构建链：

- Android SDK `android.jar`
- Android build-tools：`aapt2`、`zipalign`、`apksigner`
- R8/D8
- Java 8+
- 一个签名用 keystore

设置路径后运行：

```bash
export SDK_JAR=/path/to/android.jar
export BT=/path/to/android-sdk/build-tools/29.0.3
export R8=/path/to/r8.jar
export KEYSTORE=/path/to/release.jks
export KS_PASS='your-keystore-password'
./build.sh
```

构建脚本默认输出 APK、完整源码包和 Java 源码包。

## 说明

- 音频分析不降采样，使用音频源原生采样率。
- 视频导入只使用音轨，不播放视频画面。
- 媒体音频捕获需要 Android 10+ 和系统屏幕录制授权。
- Web Serial、部分 DRM 内容等受系统/平台限制。

## 目录

```
AndroidManifest.xml
build.sh
src/com/dsh/wavescope/
res/
icon-preview.png
```


## 许可证

本项目采用 **WaveScope Source-Available License 1.1**：

- 可自由使用、复制、分发、商用、套壳、收费
- 可闭源发布使用本库的应用程序
- **允许为移植到其他平台而修改、翻译或创作衍生版本**
- 不允许以移植之外的目的修改原库
- 必须保留版权声明和许可证

这不是 OSI 认可的开源许可证。完整条款见 [LICENSE](LICENSE)。
