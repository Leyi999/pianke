# 间歇片刻

间歇片刻，再来一组。

为 HyperOS 超级岛设计的力量训练组间计时器。练完一组点一下，组间休息自动倒计时；锁屏或切到别的 App 时，进度一直留在超级岛上。

## 功能

- 一个主按钮推进训练：完成本组 → 组间休息倒计时 → 开始下一组。随时可以切到下一个动作，点错了可以撤销。
- 组间休息 15 秒至 10 分钟，每档 15 秒；休息中调整，立即作用于当前倒计时。
- 休息结束时响铃、震动提醒，两个开关互相独立。超出的时间继续正计时，看得到多休息了多久。
- 训练中屏幕常亮；分屏时自动切换紧凑布局；应用被系统回收后重新打开，可以接着练。
- 不联网：没有网络权限，数据只存在本机，也没有任何第三方依赖。

## 超级岛

信息随岛的形态逐级增加：

| 形态 | 显示内容 |
| --- | --- |
| 小岛 | 阶段图标：训练中是杠铃，休息中是沙漏，休息超时是闪电 |
| 大岛 | 阶段图标 + 计时 |
| 展开 | 大号计时、杠铃、第几组，右侧两个按钮：⏭ 下一个动作，✓ 完成本组 / ▶ 开始下一组 |

- 杠铃代表"这一组"：杠中间的数字是第几个动作，两侧杠铃片数是第几组（第 4 组起都是 4 片），越练越"重"。
- 三个阶段三种颜色：训练 荧光绿、休息 冰蓝、休息超时 丁香紫。不用红色，避免紧迫感。
- 休息中图标外圈是进度环，随剩余时间收缩；休息结束时岛会自动展开提醒。

## 系统要求

- Android 13 及以上。
- 超级岛需要 HyperOS 3 及以上，并允许本应用显示焦点通知。
- 其他 Android 16 设备会申请"实时更新"（状态栏胶囊、锁屏置顶）；更早的系统显示为常驻通知。

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
