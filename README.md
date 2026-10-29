# SBW Radar

Superb Warfare 地面雷达附属（Minecraft 1.21.1 / NeoForge）。

## 功能

- **雷达 HUD**：战雷风格极简显示——圆盘 + 扫描扇区，顶部仅 `模式 / 扫描宽度 / 距离` 三栏
- **搜索模式**：SRC / TWS / TRK，底部只显示必要信息（跟踪数、导弹数或目标名）
- **导弹引导**：TWS 多目标跟踪与 STT 锁定，支持雷达/红外双通道
- **弹药拦截**：小型防空车（通古斯卡、铠甲-S1）可拦截来袭弹丸，大型系统不适用
- **近炸引信**：防空导弹无需直击，进入引信半径即起爆，重型/轻型半径分开配置
- **分体式防空**：雷达车锁定目标后，可指挥 **100 格内**的发射车直接开火（1.7.0）
- **机载雷达**：飞机/直升机机头雷达，固定几档扫描角度，**角度越窄刷新越快**（1.8.0）

## 机载雷达（1.8.0）

飞机与直升机默认没有雷达（防空车才有）。开启 `airborne.enable` 后，机头会挂一部**前向扇区雷达**。

设计上做了两件事：

**① 扫描角度是固定几档，不是连续值。** 默认 `30 / 60 / 120`，用「切换扫描宽度」键在档位间循环
（和地面雷达共用同一个键，但走的是 `airborne.sectors` 这套档位）。

**② 角度越窄，刷新越快。** 间隔与扇区宽度成正比：

| 扇区 | 刷新间隔（`refreshBase=10`） |
|---:|---:|
| 120° | 10 tick（0.5 s） |
| 60° | 5 tick（0.25 s） |
| 30° | 3 tick（0.15 s） |

物理直觉：窄扇区扫一遍所需时间更短，同样的天线转速下重访目标更快。

例外：**TRK 模式不参与缩放**——它用的是 8° 窄波束，本来就走 profile 的 `trkInterval`，
跟着扇区变慢反而会拖累单目标跟踪。

配置：

| 键 | 说明 |
|---|---|
| `airborne.enable` | 机载雷达总开关 |
| `airborne.sectors` | 固定档位（度），默认 `["30","60","120"]` |
| `airborne.refreshBase` | **最宽档**的刷新间隔（tick），其余档按比例缩小 |
| `airborne.range` | 机载雷达探测距离 |
| `airborne.sector` | 仅当 `airborne.sectors` 为空时的兜底值 |

切换档位时 actionbar 会显示刷新率，例如 `雷达扫描宽度: 30°（3 tick 刷新）`。

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
- **雷达车自己也能打**（`split.selfFire=true`）：锁上就自动发射本车导弹，驾驶员不需要扣扳机；
  `split.onlyUnmanned` 不管制雷达车本身——驾驶员正是持锁的人，自动开火才是预期行为。
- **默认跳过有乘员的发射车**（`split.onlyUnmanned=true`），有人坐在里面时保留手动接管。
- **打不出去会说原因**（`split.report=true`）：锁定中但没开火时，actionbar 每 2 秒提示一次，
  区分「半径内没有载具 / 都没装导弹 / 发射车有乘员 / 冷却中」。
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
| `split.selfFire` | 雷达车自身也自动发射（默认 true） |
| `split.report` | 锁定未开火时在 actionbar 说明原因（默认 true） |
| `airborne.enable` / `airborne.range` | 机载雷达开关与距离 |
| `airborne.sectors` / `airborne.refreshBase` | 固定扫描档位 / 最宽档刷新间隔 |

## 编译

把以下 jar 放入 `libs/`（不随仓库分发）：

`minecraft-neoforge-compiled-*.jar`、`loader-*.jar`、`bus-*.jar`、`modlauncher-*.jar`、
`superbwarfare-*.jar`、`joml`、`gson`、`slf4j-api`、`datafixerupper`、`mergetool-api`

```bash
gradle jar            # 产物在 build/libs/
```
