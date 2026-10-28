# SBW Radar

Superb Warfare 地面雷达附属（Minecraft 1.21.1 / NeoForge）。

## 功能

- **雷达 HUD**：战雷风格极简显示——圆盘 + 扫描扇区，顶部仅 `模式 / 扫描宽度 / 距离` 三栏
- **搜索模式**：SRC / TWS / TRK，底部只显示必要信息（跟踪数、导弹数或目标名）
- **导弹引导**：TWS 多目标跟踪与 STT 锁定，支持雷达/红外双通道
- **弹药拦截**：小型防空车（通古斯卡、铠甲-S1）可拦截来袭弹丸，大型系统不适用
- **近炸引信**：防空导弹无需直击，进入引信半径即起爆，重型/轻型半径分开配置
- **分体式防空**：雷达车锁定目标后，可指挥 **100 格内**的发射车直接开火（1.7.0 新增）

## 分体式防空（1.7.0）

现实里的远程防空系统多是分成两辆车的：雷达/指挥车 + 发射车。SBW 把它们当作两个独立实体，
所以发射车单独是"瞎子"——没有雷达就没有锁定。

这个功能在服务端把两者接起来：

1. 雷达车进入 **TRK** 模式并持有锁定（`ServerRadarHandler.trackedTargetId`）
2. 每 `split.interval` tick，在 `split.radius`（默认 **100 格**）内找发射车
3. 发射车必须已装 **导弹类弹药**（`RadarMissileControl.missileGun` 挑选第一个导弹武器）
4. 通过玩家开火所用的**同一条制导链路**替它发射（`RadarMissileControl.launchFromLauncher`）
5. 该发射车进入 `split.cooldown` 冷却

几个设计取舍：

- **导弹从发射车出膛，但制导权归雷达车**——`Guided.radarVehicleId` 记的是雷达车，
  所以通道数上限、中段指令更新都还算在雷达车上，符合真实的数据链分工。
- **默认跳过有乘员的发射车**（`split.onlyUnmanned=true`），有人坐在里面时保留手动接管。
- 只接管**雷达制导**弹药；红外弹仍走 `IrMissileControl`，被指挥的是"雷达弹"这个概念。
- 雷达车驾驶员会收到 `发射车 ×N 已开火` 的 actionbar 提示。

生效前提：`missile.enable=true`（导弹引导总开关）且雷达车真的在 TRK 锁着目标。

## 配置

`config/sbw_radar-common.toml`

| 键 | 说明 |
|---|---|
| `interception.vehicles` | 允许拦截弹丸的载具实体 id |
| `interception.radius` / `perBurst` / `cooldown` | 拦截半径、单次目标数、冷却 tick |
| `proximity.heavyMissiles` / `heavyRadius` | 重型防空导弹与引信半径 |
| `proximity.lightMissiles` / `lightRadius` | 轻型防空导弹与引信半径 |
| `split.enable` | 分体式防空总开关（默认 true） |
| `split.radius` | 雷达车指挥半径，默认 **100** 格 |
| `split.interval` / `split.cooldown` | 搜索间隔 / 单台发射车冷却（tick） |
| `split.onlyUnmanned` | 只指挥无人发射车（默认 true） |

## 编译

把以下 jar 放入 `libs/`（不随仓库分发）：

`minecraft-neoforge-compiled-*.jar`、`loader-*.jar`、`bus-*.jar`、`modlauncher-*.jar`、
`superbwarfare-*.jar`、`joml`、`gson`、`slf4j-api`、`datafixerupper`、`mergetool-api`

```bash
gradle jar            # 产物在 build/libs/
```
