# ServerFlashback

[English](README.md) | **简体中文**

服务端 Fabric 模组，以 FlashBack 原生格式录制 Minecraft 回放。支持区域录制，录制范围可超出服务器已加载区块。输出文件可直接被 [FlashBack](https://github.com/Flashback-MC/Flashback) 客户端模组用于播放。

## 支持版本

- Minecraft 1.17.1
- Minecraft 1.21.4

## 依赖

- Fabric Loader
- Fabric API
- 仅服务端（录制无需客户端安装）

## 安装

1. 从 [Releases](https://github.com/Trirrin/ServerFlashback/releases) 下载对应 Minecraft 版本的构建
2. 将 JAR 放入服务器的 `mods` 文件夹
3. 重启服务器

## 命令

所有命令需要 OP 等级 2：

| 命令 | 说明 |
|------|------|
| `/serverflashback start <坐标> <半径> [名称]` | 在指定方块坐标开始录制，半径 16–4096 格 |
| `/serverflashback stop [名称]` | 停止录制。省略名称时，若仅有一个活跃录制则停止该录制 |
| `/serverflashback pause [名称]` | 暂停录制 |
| `/serverflashback resume [名称]` | 恢复暂停的录制 |
| `/serverflashback list` | 列出活跃录制 |
| `/serverflashback mark <名称> [描述]` | 向当前录制添加标记点（需以玩家身份执行） |

## 输出

回放文件（`.zip`）保存在 `<服务器根目录>/serverflashback/replays/`。使用 FlashBack 客户端模组打开即可播放。

## 构建

```bash
./gradlew :1.21.4:build              # 构建 MC 1.21.4 版本
./gradlew :1.17.1:build --no-parallel # 构建 MC 1.17.1 版本
./gradlew :1.21.4:compileJava        # 仅编译（更快）
```

## 许可证

GPL-3.0
