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
