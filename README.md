# Writable Book Wiki

面向 **Minecraft 1.21.1 / NeoForge** 的可编写 Wiki 书 Mod：以 Word 式所见即所得编辑正文，用目录组织内容，并将个人批注保存在玩家本机。

- 模组名称：`Writable Book Wiki`
- MODID：`writablebookwiki`
- Java：21
- 当前构建目标：NeoForge 21.1.250
- 不需要 LDLib2 或额外 UI 前置 Mod。

这是一个新 Mod，数据只使用自己的 `writablebookwiki` 命名空间，不提供旧 Mod／历史开发版本的数据适配或自动迁移。

## 源码模块

| 目录 | 负责什么 |
| --- | --- |
| [`core`](core/) | 文档结构、富文本样式、文字排版、编辑命令、撤销／重做、内容 Hash；不依赖 Minecraft。 |
| [`local`](local/) | 本机图片资源、个人批注、正文缓存、界面设置和 DOCX 文件读写；不依赖 Minecraft。 |
| [`plugin-api`](plugin-api/) | 为将来的可替换后端／插件接口预留独立模块。目前仅有模块占位，不代表已提供可用的服务端插件。 |
| [`neoforge`](neoforge/) | NeoForge 入口、书物品、游戏内 UI、客户端／服务端同步和世界存档读写。 |

这些目录是开发分工，不是四个需要分别安装的 Mod。构建时，前三个模块的代码会合进 `neoforge` 导出的 **一个 Mod JAR**。玩家只需将最终 JAR 放进游戏实例的 `mods` 文件夹；多人使用时，客户端和服务器都需要安装对应版本。

## 本地数据存在哪里

**数据不写进 JAR，也不存进源码目录 `local/`。**

下文的 `<游戏实例>` 指启动器为当前版本／整合包指定的游戏目录，可能是 `.minecraft`，也可能是单独的版本隔离目录。可使用启动器的“打开游戏目录”定位，不要直接把它理解为电脑上唯一的 `.minecraft`。

### 玩家本机

共同根目录：`<游戏实例>/config/writablebookwiki/`。

| 相对该目录的位置 | 内容 |
| --- | --- |
| `annotations/` | 玩家自己的文字范围批注，不上传服务器，也不写入共享正文。 |
| `assets/` | 导入的本机图片，以及从服务器下载的图片缓存。图片复制进资源库，不依赖原始文件路径。 |
| `book-cache/` | 已下载并校验过的世界书正文缓存，不是服务器的正式存档。 |
| `layout.properties` | 目录、正文与批注模块的布局设置。 |
| `appearance.properties` | 纸张颜色等本机外观设置。 |
| `preview-document.ewd` | F8 本地预览文档，不是世界内实体书的正式正文。 |
| `temporary/` | 图片导入、剪贴板处理等产生的临时文件。 |

这些目录和文件按实际使用创建，刚安装时不一定全部存在。DOCX 导入读取玩家选中的文件，导出则写入玩家选择的保存位置。

### 世界／服务器

实体书的正式正文和图片跟随世界保存，不放在玩家的个人批注目录：

| 位置 | 内容 |
| --- | --- |
| `<世界存档>/writablebookwiki/documents/` | 世界中书籍的正文文件。 |
| `<世界存档>/writablebookwiki/assets/` | 书籍使用的世界图片资源。 |
| `<世界存档>/data/writablebookwiki_book_index.dat` | 书籍索引。 |

单机世界通常在 `<游戏实例>/saves/<世界名>/`；专用服务端通常在服务端目录下的 `world/`，具体取决于服务端的世界目录设置。多人游戏中，这部分正式数据在服务器上，个人批注仍在各个玩家的电脑上。

传输配置的默认位置是 `<游戏实例>/config/writablebookwiki-server.toml`（专用服务端对应其 `config` 目录），用于调整分发速度和单次分发大小；`0` 表示不设置对应的日常配额，协议层必要的安全边界仍保留。

### 备份建议

- 想保留自己的批注、图片和界面设置：备份本机的 `config/writablebookwiki/`。
- 想保留世界里的实体书：备份完整世界存档，不能只备份玩家本机缓存或 Mod JAR。
- 想保留传输配置：另行备份 `config/writablebookwiki-server.toml`。

## 构建

安装 Java 21 后，在项目根目录使用 Gradle Wrapper：

```powershell
.\gradlew.bat :core:test :local:test :neoforge:build
```

Linux／macOS 使用 `./gradlew`。正式 Mod JAR 位于 `neoforge/build/libs/`，命名格式为 `Writable Book Wiki-<版本>-NeoForge-1.21.1.jar`；不要把 `-sources.jar` 当作游戏 Mod 安装。

本库包含源码、构建文件、自动测试、测试资源及本说明；不包含玩家存档、个人配置、缓存或开发运行环境。
