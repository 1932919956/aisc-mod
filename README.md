# Agnes 女仆伙伴

Agnes 女仆伙伴是一款适用于 Minecraft 1.20.1 Forge 的 AI 生存辅助 Mod。它为车万女仆加入 Agnes AI 规划、视觉观察、行动记忆和自主生存能力，让女仆可以根据真实的游戏状态安排自己的下一步行动。

## 功能

- 自主采集原木、石头、煤炭和铁矿
- 制作工具、装备武器并管理真实背包
- 主动追击和狩猎野生动物
- 箱子、木桶存取与物品整理
- 安全阶梯式挖掘
- 固定蓝图建造小屋、石屋和菜园
- 视觉观察、行动结果记录和地标记忆
- 原生女仆对话气泡和思考气泡
- 受伤救援、熔炉烧炼和基础生存循环
- 与 Promaid 1.3.0 协作，不取消 Agnes 的采集和挖掘
- 支持 Agnes、智谱 GLM-4.6V-Flash 和 OpenAI 兼容接口

女仆的行动由游戏中的真实背包、生命值、食物、方块和距离决定。Mod 不会凭空生成资源，也不能保证自动通关原版 Minecraft。

## 运行要求

- Minecraft 1.20.1
- Minecraft Forge 47.x，推荐 47.4.18
- Java 17
- Touhou Little Maid 1.5.3 Forge 版
- Agnes API Key，以及可以访问接口的网络

Promaid 1.3.0 是可选的第三方 Mod，不包含在本源码仓库中。

## 安装使用

1. 安装 Minecraft 1.20.1 Forge 和车万女仆 1.5.3。
2. 将编译得到的 `agnespartner` JAR 放入游戏实例的 `mods` 文件夹。
3. 启动游戏，在 Mod 列表中打开 Agnes AI Partner 的配置界面。
4. 填写自己的 Agnes API Key，保存后重新进入世界。
5. 获得并驯服一只车万女仆，然后在聊天框执行：

```text
/aipartner maid bind
/aipartner autonomy on
```

常用指令：

```text
/aipartner autonomy off       # 关闭自主行动
/aipartner status              # 查看当前计划和执行状态
/aipartner inventory           # 查看女仆背包
/aipartner maid sight          # 查看女仆当前观察
/aipartner maid landmarks      # 查看女仆记住的地标
/aipartner maid unbind         # 解除绑定
```

## API 配置

仓库内不包含任何个人 API Key，默认配置全部为空。首次启动后，配置文件会生成在：

```text
游戏目录/config/agnespartner-portable.toml
```

也可以在游戏内打开 `Mods → Agnes AI Partner → Config` 填写。主要配置项包括：

```toml
apiKey = ""
model = "agnes-2.5-flash"
zhipuApiKey = ""
zhipuModel = "GLM-4.6V-Flash"
customApiUrl = ""
customApiKey = ""
customModel = ""
```

智谱和通用接口属于可选备用服务。不要把填写过密钥的 `config` 文件、日志、截图或缓存提交到 GitHub。

## 从源码构建

将车万女仆依赖 JAR 放入：

```text
libs/touhoulittlemaid-1.5.3.jar
```

使用 Java 17 和 Gradle 8.11.1，在项目根目录执行：

```powershell
gradle build
```

便携版构建：

```powershell
gradle -Pportable build
```

输出文件位于 `build/libs/`。验证代码位于 `src/gametest/`，普通构建不会将验证专用内容打入正式发布包。

## 项目目录

```text
src/             Mod 源码和资源
docs/            安装与功能说明
config-template/ 空白配置模板
build.gradle     ForgeGradle 构建配置
LICENSE          MIT 许可证
```

## 许可证

本项目使用 MIT License，详见 [LICENSE](LICENSE)。车万女仆和 Promaid 的代码、资源及许可证归各自作者所有。
