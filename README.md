# 间歇片刻

间歇片刻，再来一组。

为 HyperOS 超级岛设计的力量训练组间计时器。练完一组点一下，组间休息自动倒计时；锁屏或切到别的 App 时，进度一直留在超级岛和锁屏上。

## 功能

- 一个主按钮推进训练：完成本组 → 组间休息倒计时 → 开始下一组。随时可以切到下一个动作，点错了可以撤销。
- 组间休息 15 秒至 10 分钟，每档 15 秒；休息中调整，立即作用于当前倒计时。
- 休息结束时响铃、震动提醒，两个开关互相独立。超出的时间继续正计时，看得到多休息了多久。
- 训练中屏幕常亮；分屏时自动切换紧凑布局；应用被系统回收后重新打开，可以接着练。
- 不联网：没有网络权限，数据只存在本机，也没有任何第三方依赖。

## 超级岛与锁屏

信息随岛的形态逐级增加：

| 形态 | 显示内容 |
| --- | --- |
| 小岛 | 图标 |
| 大岛 | 图标 + 计时 |
| 展开 / 锁屏卡片 | 一句状态短语；动作、组、计时三栏大字；两个按钮：下一个动作、完成本组（休息中为开始下一组） |

- 图标和应用图标同款：圆环里一副杠铃。杠铃片数是已经练到第几组（第 4 组起都是 4 片），开始下一组时才加一片，换到下一个动作时是空杠。圆环表示阶段：训练中 3/4 圈，休息中随剩余时间收缩，超时是满圈。
- 状态短语：训练 Light weight，休息 Breathe，休息超时 Ready when you are。
- 配色只用白、灰、黑：训练白，休息灰。不用红色，避免紧迫感。

## 系统要求

- Android 13 及以上。
- 超级岛：Android 16 及以上的 HyperOS 3+，通过系统的"实时更新"上岛，不需要额外授权。Android 17 起，展开态和锁屏卡片的计时是系统大字，由系统走秒。
- 其他 Android 16 及以上的设备：状态栏胶囊、锁屏置顶。更早的系统：常驻通知。

## 下载

到 [Releases](https://github.com/Leyi999/pianke/releases) 下载 APK 安装。

发布版签名证书的 SHA-256，可以用 `apksigner verify --print-certs` 核对：

```
0113388f691f5844432bd0ecae15e35b8d971b8a8636cff389555a86f839a854
```

## 编译

需要 JDK 17 和 Android SDK（Platform 36、Build-Tools 35.0.0）。

```sh
git clone https://github.com/Leyi999/pianke.git
cd pianke
./gradlew assembleRelease
```

安装包在 `app/build/outputs/apk/release/`。没有签名配置时自动使用 debug 签名，可以直接安装测试，但不能覆盖安装 Releases 里的版本（签名不同）。

使用自己的发布密钥：新建 `keystore/keystore.properties`（`keystore/` 已被 .gitignore 忽略）：

```properties
storeFile=keystore/release.jks
storePassword=你的密钥库密码
keyAlias=你的密钥别名
keyPassword=你的密钥密码
```

## 参考

- [HyperOS 焦点通知 / 超级岛开发文档](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)

## 许可

[MIT](LICENSE)。个人项目，非小米官方应用。
