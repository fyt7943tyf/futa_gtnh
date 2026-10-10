<div align="center">

# FutaGTNH

**GTNH（GregTech: New Horizons）附属模组 · Minecraft 1.7.10**

[![CI 构建](https://github.com/fyt7943tyf/futa_gtnh/actions/workflows/ci.yml/badge.svg)](https://github.com/fyt7943tyf/futa_gtnh/actions/workflows/ci.yml)
[![最新版本](https://img.shields.io/github/v/release/fyt7943tyf/futa_gtnh?label=release&sort=semver)](https://github.com/fyt7943tyf/futa_gtnh/releases)
![Minecraft](https://img.shields.io/badge/Minecraft-1.7.10-blue)
![Forge](https://img.shields.io/badge/Forge-10.13.4.1614-orange)

一个给 GTNH 服务器添彩的杂烩模组：全服共享背包、俯瞰建筑（RTS 式建造）、迅步、寻物魔杖、小游戏助手、抽奖机等。

</div>

## 功能模块

| 功能 | 入口 | 说明 | 详细文档 |
|---|---|---|---|
| **全服共享背包** | 按键 **B** / 「共享终端」方块 | 全服所有人共用的无限物品与流体存储，支持搜索（含拼音）、排序、分页，与个人背包双向交换，可对接 GT 管道自动化 | [README-共享背包.md](README-共享背包.md) |
| **俯瞰建筑** | 按键 **G** | 像 RTS 游戏一样从头顶俯瞰操作世界：远程互动、批量形状建造（直线/墙面/圆盘/球体等）与区域破坏、撤销重做，服务端全程校验 | [README-俯瞰建筑.md](README-俯瞰建筑.md) |
| **迅步** | 饰品（可装备到 Baubles 槽） | 移动/飞行加速、自带火把照明、作物与动物生长光环、生命/饱食恢复、掉落物吸附 | — |
| **寻物魔杖** | 物品 | 右键打开选择界面追踪目标，佩戴饰品槽时持续显示追踪光线。矿脉搜索会先查 VisualProspecting 的矿脉记录，因此**没加载的区块里已生成的矿脉也能找到**（装 VP 时；未安装则退回只扫已加载区块） | — |
| **小游戏助手** | 物品 | 全服共享的地牢小游戏清单：搜索附近（零开销种子推算）、标记已完成、一键传送到地牢入口；配合 LootGames 联动：无限重试 + 第 N 次失败按满奖励结算、潜行右击游戏主方块跳过当前关（主方块在棋盘边框西北外角、与地板同外观，潜行右击棋盘格子时会提示一次位置；LootGames 联动） | — |
| **自选抽奖机** | 方块 | 自选奖池的抽奖机（Enhanced LootBags 联动） | — |
| **太阳能除钙剂** | 物品 | 右键蒸汽太阳能锅炉即可重置钙化进度，可无限使用 | — |
| **低压拆解机** | LV GT 单方块机器 | 固定 32 EU/t、1A、2 秒，采用 GTNL 微光转换规则，单步返还 GT 机器工作台、组装机、装配线及太空组装机材料，分页物品输出和多个真实流体罐 | [README-拆解机.md](README-拆解机.md) |
| **合成向导（手机网页）** | 按键 **P** / `/futaweb` | 客户端内嵌一个只读网页服务：手机浏览器打开就能搜任意物品、看它在 NEI 里的全部做法、自选配方、填数量，拿到一份扣掉共享背包与个人背包库存后的分步合成指导 | [README-合成向导.md](README-合成向导.md) |

> 抽奖机、小游戏助手等功能需要安装对应的联动模组才会生效（见下方可选依赖）。
> 合成向导的配方数据全部来自 NEI，没装 NEI 时这一块不启用。

## 安装

1. 前往 [Releases](https://github.com/fyt7943tyf/futa_gtnh/releases) 下载最新的 `futa_gtnh-<版本>.jar`；
2. 放进 GTNH 整合包的 `mods/` 文件夹，**客户端与服务端都要装**；
3. 硬性前置：**GregTech（GT5-Unofficial）**、**lwjgl3ify** —— GTNH 整合包自带，无需额外操作；
4. 可选联动（装了自动增强，不装不影响运行）：NEI、NotEnoughCharacters（拼音搜索）、MouseTweaks、Tinkers' Construct、Baubles-Expanded、LootGames 2.2.14+、Enhanced LootBags、Vending Machine。

## 从源码构建

环境要求：**JDK 17+**（推荐 JDK 25，与 CI 保持一致）。

```bat
setup.bat          rem 首次：建立 Minecraft/GTNH 开发工作区
gradlew.bat build  rem 构建，产物在 build\libs\futa_gtnh-<版本>.jar
```

开发客户端 / 服务端请用 `gradlew.bat runClient17` / `runServer17`（本项目使用 lwjgl3ify，不要用 `runClient`）。依赖、Mixin、目录结构等开发细节见 [README-开发环境.md](README-开发环境.md)。

版本号只有一个来源：根目录的 `version.txt`，改它就行。

## CI / CD（GitHub Actions）

| Workflow | 触发条件 | 作用 |
|---|---|---|
| [CI 构建](.github/workflows/ci.yml) | push / PR 到 `main` | 编译并执行 Spotless、Checkstyle 检查，构建产物保留 7 天 |
| [发版](.github/workflows/release.yml) | `version.txt` 变化推送到 `main`、推送 `v*` tag、手动触发 | 自动打 tag、创建 GitHub Release（自动生成变更日志）并附上 jar |

### 发一个新版本

日常发版只需两步：

1. 把 `version.txt` 改成新版本号（例如 `1.5.0` → `1.5.1`）；
2. 提交并推送到 `main`。

发版 workflow 会自动检测到版本变化，创建 tag `v1.5.1` 并发布带 jar 的 Release。

需要手动控制时也有两种兜底方式（版本号必须与 `version.txt` 一致）：

```bash
git tag v1.5.1 && git push origin v1.5.1   # 推 tag 触发
# 或到 GitHub 仓库 Actions 页面手动运行「发版」workflow
```

## 目录结构

```
futa_gtnh/
├─ src/main/java/com/futa_gtnh/   模组代码（入口 FutaGtnhMod.java）
├─ src/main/resources/            mcmod.info、Mixin 配置、语言文件与材质
├─ docs/                          开发笔记
├─ tools/                         辅助工具
├─ gtnhShared/                    Spotless / Checkstyle 格式化配置
├─ vendor/                        钉死版本的联动兼容 jar（CI 编译依赖，见 vendor/README.md）
└─ version.txt                    版本号（唯一来源）
```

## 开发规范

提交规范、代码风格、验证要求见 [AGENTS.md](AGENTS.md)。简要来说：构建自带 Spotless / Checkstyle 检查；提交信息使用中文动作前缀（`新功能：`、`修：`、`README：` 等）。

## 文档索引

- [README-共享背包.md](README-共享背包.md) —— 共享背包的完整玩法、按键与故障排查
- [README-俯瞰建筑.md](README-俯瞰建筑.md) —— 俯瞰建筑模式的全部功能与配置
- [README-合成向导.md](README-合成向导.md) —— 手机网页版配方查询与分步合成指导
- [README-开发环境.md](README-开发环境.md) —— 从零搭建开发环境
- [AGENTS.md](AGENTS.md) —— 仓库结构与提交规范
