# JZ 微信授权桥（LSPosed 模块）

在 `com.xtc.watch` 小天才家长端内复用宿主已有的微信 SDK。手机网页发起请求后，模块在小天才里显示来源确认，再打开微信完成 `snsapi_userinfo` 授权，并将真实 `code/state` 返回给该网页。

当前版本用于验证“网页调起微信并接收授权信息”。它带有独立模块界面，会显示最近一次拦截到的真实授权结果；不会自动把授权码兑换成 JZ 家长账号登录，也不会保存授权码。

## 安装与启用

1. 安装 `app-debug.apk`。APK 同时提供“JZ 微信授权桥”桌面入口，用于查看最近一次拦截结果。
2. 打开 LSPosed，进入“模块”，选择 **JZ 微信授权桥** 并启用。
3. 作用域只勾选 **小天才家长端**（`com.xtc.watch`）。
4. 彻底关闭并重新打开小天才家长端，让模块注入新进程。
5. 每次捕获到微信授权响应，模块会打开凭证界面。也可以从桌面图标手动打开；它只显示当前运行期间的内存数据。如需确认加载结果，在 LSPosed 日志中查找：

   ```text
   [JZWechatBridge] loaded in com.xtc.watch
   ```

LSPosed 不需要重打包或重签名小天才 APK，因此不会引入 LSPatch 重签名导致的微信签名校验变化。

## 网页使用

网页必须在**同一台手机**的浏览器中打开，因为桥接地址是手机自己的回环地址：

```text
http://127.0.0.1:38787
```

ES 模块客户端位于 `client/wechat-bridge.js`：

```js
import { requestWechatAuthorization } from './wechat-bridge.js';

const { code, state } = await requestWechatAuthorization({
  onStatus(snapshot) {
    console.log(snapshot.status); // 不输出 code
  },
});

// code 是一次性凭证，应立即交给你自己的后端换票，不要持久化。
```

也可以从 `client/` 目录启动一个静态 HTTP 服务，用手机访问 `demo.html` 做真实授权联调。不要直接用 `file://` 打开，因为模块会拒绝 `Origin: null`。

完整流程：

1. 网页检查模块状态并生成 32 字节随机 `state`。
2. 网页请求模块，小天才显示请求来源。
3. 用户确认后，模块通过宿主的 `IWXAPI.sendReq()` 打开微信。
4. 用户在微信中授权。
5. 回到网页后，网页轮询取得一次性 `code`，并校验 `state`。

如果 Android 阻止后台打开小天才，网页停留在 `waiting_for_host`；手动打开小天才即可继续。

## 凭证预览界面\n\n捕获成功后会自动打开模块界面，并显示以下内容：\n\n- 真实微信授权 `code`，默认遮罩，可手动显示或复制。\n- 完整 `state`，可单独复制。\n- 请求来源、微信错误码、捕获时间和小天才版本。\n\n界面使用模块自己的 Activity，不依赖小天才 WebView。`code/state` 只保存在 Activity 内存中，不写入日志、SharedPreferences 或文件。窗口设置了 `FLAG_SECURE`，系统最近任务缩略图和录屏不会显示凭证。再次捕获或点击“清空当前凭证”会覆盖或清除当前内容。\n\n## 安全边界

- 服务只绑定 `127.0.0.1`，局域网其他设备无法直接连接。
- 每次授权都需要用户在小天才里确认完整网页来源。
- `Origin`、`Host` 和随机 `state` 均校验，支持浏览器 CORS 与 Private Network Access 预检。
- 同时只允许一个进行中的请求，请求两分钟后过期。
- 成功和失败结果都只能由原来源读取一次。
- 桥接响应不会进入小天才原有的登录广播，避免宿主先消费一次性授权码。
- 宿主自身的 `login_state`、账号绑定、分享和支付流程不受影响。
- 授权码不写日志、不落盘，模块不包含也不处理微信 AppSecret。

## 构建与验证

需要 JDK、Android SDK 和网络连接：

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest assembleDebug
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

项目使用旧版 Xposed API 82 作为 `compileOnly` 依赖，`assets/xposed_init` 指向 `XposedEntry`。该格式兼容 LSPosed，且 API 桩不会进入 APK。

## 当前验证范围

- Android Java 编译和 Debug APK 签名打包已通过。
- 授权状态、来源隔离、单次读取、过期和取消等 8 项单元测试已通过。
- 尚未在你的手机与小天才版本上做真实微信回调验证；实机结果以微信返回为准。

若模块已加载但授权时报 `host_incompatible`，请提供小天才版本和 LSPosed 中 `[JZWechatBridge]` 附近的错误日志。

