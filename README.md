# TVBoxMobile

基于
* [q215613905](https://github.com/q215613905)/[TVBoxOS](https://github.com/q215613905/TVBoxOS)   

## Build
[Github Actions](https://github.com/XiaoRanLiu3119/MBox-Build/actions)   

精力有限,未必会及时维护,仅用于学习
   
## 推荐使用   
[takagen99](https://github.com/takagen99/Box)   
[FongMi](https://github.com/FongMi/TV)   

## TV 双模式

本分支 (`feature/tv-adaptation`) 在保留手机 UI 的前提下增加 TV 操作界面：

- **单 APK 运行时切换**：设置 → 界面模式 → 自动 / 手机 / TV（修改后需重启应用）
- **TV 壳层**：`TvMainActivity` 侧栏导航 + D-pad 焦点浏览
- **WebDAV 同步**：核心层与手机版共用，TV 设置页可配置与立即同步
- **Gradle flavor**（可选独立 TV 包）：
  - `./gradlew assembleMobileDebug` — 手机 flavor
  - `./gradlew assembleTvDebug` — TV flavor（含 `LEANBACK_LAUNCHER`）

### 无电视/遥控器测试

1. 设置 → 界面模式 → **TV**，重启应用
2. Android 模拟器：方向键 = D-pad，Enter = 确认，Esc = 返回
3. ADB：`adb shell input keyevent 19/20/21/22/23`

### 验收要点

| 场景 | 手机模式 | TV 模式 |
|------|----------|---------|
| 首页浏览/详情/播放 | 必测 | 必测 |
| 直播 D-pad | — | 必测 |
| WebDAV 配置与同步 | 必测 | 必测 |
