# SBW Radar

Superb Warfare 地面雷达附属（Minecraft 1.21.1 / NeoForge）。

## 功能

- **雷达 HUD**：战雷风格极简显示——圆盘 + 扫描扇区，顶部仅 `模式 / 扫描宽度 / 距离` 三栏
- **搜索模式**：SRC / TWS / TRK，底部只显示必要信息（跟踪数、导弹数或目标名）
- **导弹引导**：TWS 多目标跟踪与 STT 锁定，支持雷达/红外双通道
- **弹药拦截**：小型防空车（通古斯卡、铠甲-S1）可拦截来袭弹丸，大型系统不适用
- **近炸引信**：防空导弹无需直击，进入引信半径即起爆，重型/轻型半径分开配置

## 配置

`config/sbw_radar-common.toml`

| 键 | 说明 |
|---|---|
| `interception.vehicles` | 允许拦截弹丸的载具实体 id |
| `interception.radius` / `perBurst` / `cooldown` | 拦截半径、单次目标数、冷却 tick |
| `proximity.heavyMissiles` / `heavyRadius` | 重型防空导弹与引信半径 |
| `proximity.lightMissiles` / `lightRadius` | 轻型防空导弹与引信半径 |

## 编译

把以下 jar 放入 `libs/`（不随仓库分发）：

`minecraft-neoforge-compiled-*.jar`、`loader-*.jar`、`bus-*.jar`、`modlauncher-*.jar`、
`superbwarfare-*.jar`、`joml`、`gson`、`slf4j-api`、`datafixerupper`、`mergetool-api`

```bash
gradle jar            # 产物在 build/libs/
```

---

## 第三方依赖与许可

本项目是 [Superb Warfare](https://github.com/atsuishio/superbwarfare) 的附属模组，运行时依赖该模组。

| 项 | 说明 |
|---|---|
| 上游模组 | Superb Warfare（Atsuishio、Roki27、Light_Quanta 及贡献者） |
| 上游许可 | **GNU GPL v3.0**（见其发布 jar 内 `COPYING` / `COPYING.LESSER`） |
| 本仓库内容 | 仅为附属功能的原创实现，通过上游公开 API 交互 |
| 是否包含上游文件 | **否**。不含 Superb Warfare 的源码、资源或二进制 |

因运行时链接 GPL-3.0 组件，本附属同样以 **GPL-3.0** 分发。若需其他许可安排，请自行确认合规性。

本仓库**不包含**任何第三方游戏资源（War Thunder 模型与贴图等），也不包含构建所需的依赖 jar（`libs/` 已列入 `.gitignore`）。
