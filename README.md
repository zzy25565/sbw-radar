# SBW Radar

当前版本 **1.11.1**（2026-10-29）。[下载正式版](https://github.com/zzy25565/sbw-radar/releases/tag/v1.11.1-loadout)；空空导弹联动配套 [sbw-loadout 1.3.4](https://github.com/zzy25565/sbw-loadout/releases/tag/v1.3.4)。客户端和服务端同时更新两个 JAR，移出旧版。

本次仅接入挂载模组的空空导弹：AIM-54A/C、PL-12/12A、R-40RD/TD、R-73/73L、R-77/77-1。雷达弹共用 TRK / TWS、照射通道与箔条；红外弹共用热目标锁定与热诱弹，直接登记已发射的挂载弹体，不另外生成导弹。Kh-38MT 空地弹和航弹不接入空空红外接口；既有 9M336 雷达联动保留。

基于 GitHub 1.11.0 合并，保留分体式防空、自主发射及原因提示；挂载飞机/直升机继续由驾驶员手动发射，不进入地面防空自主流程，也不允许通过原生远程发射绕过挂载库存。自主地面弹体缺少玩家 UUID 时不会在末段搜索中抛异常。

空空弹爆炸伤害由配套挂载 1.3.4 降低为旧值的 50%，直击和爆炸半径保留。联动近炸调用弹体自身 SBW 爆炸，避免绕过弹药伤害。自机、乘员、友军及遮挡目标不触发联动近炸；箔条/热诱后脱锁状态保留至相应弹体存档。

默认 O 切模式、J 切 TRK 目标、P 切宽度、G 热诱弹、H 箔条。修复单独按 G/H 被提前忽略的问题。已有 options.txt 不自动重写，旧 K/L 键位请自行调整。SBW 将无队伍驾驶员的载具列为中立；雷达导弹只接受服务端敌方航迹。联合操作和验收见 [挂载适配说明](https://github.com/zzy25565/sbw-loadout/blob/main/docs/空空导弹适配_1.3.4.md)。

Superb Warfare 地面雷达附属（Minecraft 1.21.1 / NeoForge）。

## 功能

- **雷达 HUD**：战雷风格极简显示——圆盘 + 扫描扇区，顶部仅 `模式 / 扫描宽度 / 距离` 三栏
- **搜索模式**：SRC / TWS / TRK，底部只显示必要信息（跟踪数、导弹数或目标名）
- **导弹引导**：TWS 多目标跟踪与 STT 锁定，支持雷达/红外双通道
- **弹药拦截**：小型防空车（通古斯卡、铠甲-S1）可拦截来袭弹丸，大型系统不适用
- **近炸引信**：防空导弹无需直击，进入引信半径即起爆，重型/轻型半径分开配置
- **分体式防空**：雷达车锁定目标后，可指挥 **100 格内**的发射车直接开火（1.7.0）
- **机载雷达**：飞机/直升机机头雷达，固定几档扫描角度，**角度越窄刷新越快**（1.8.0）
- **竖向扫描范围**：雷达有了俯仰锥，游戏内按 `;` 切档，上限可配（1.9.0 / 1.10.0）
- **干扰弹内置**：热诱弹走 SBW 车载诱饵池，箔条独立，存在储物空间、HUD 分两行显示（1.11.0）

## 干扰弹：内置 + 用原生 UI（1.11.0）

原来干扰弹是"玩家背包里的物品"，换车就没了。现在改成**随车走**，存在载具的储物空间里。

| 弹种 | 弹药来源 | 发放方式 | 数量显示 |
|---|---|---|---|
| **热诱弹** | `superbwarfare:flying_flare_ammo`（SBW 原生） | 直接调 `vehicle.releaseDecoy()`，用 SBW 的发射源/冷却/诱饵实体 | 原生 UI 的 `DECOY_COUNT` |
| **箔条** | `sbw_radar:chaff`（本附属） | 从载具容器 `extractItem` 扣 1，再走我们自己的概率判定 | 随 `StateSync` 下发 |

关键点：

- 热诱弹**完全交给 SBW**——发射逻辑、冷却、诱饵实体、原生 UI 都复用它，我们只负责扣扳机。
  原生 UI 的文案（`tips.superbwarfare.flare.*`）在本附属的 lang 里覆盖成了"热诱弹 就绪/装填中/无热诱弹"。
  若游戏内仍显示"干扰弹"，那是语言文件合并顺序问题，用资源包覆盖这 3 个键即可。
- 箔条是**独立弹种**，SBW 没有对应概念（它的 `smoke_decoy` 是烟幕弹，只遮挡视线，不干扰雷达弹），
  所以数量与判定都归本附属，HUD 在原生行下方单独一行显示。
- 载具容器没有物品白名单（`canPlaceItem` 只做堆叠校验），所以箔条能直接放进储物空间。
- 找不到容器时（少数载具）自动回落到玩家背包，不会因为没容器就用不了。

## 竖向扫描范围（1.9.0）

上游 `RadarScanner.RadarConfig` **只有水平 `sweepAngle`**，没有俯仰字段——原来的竖向限制只有
`radar.minTargetHeight` / `maxTargetHeight` 这个"绝对高度窗口"，跟雷达朝向无关。

这个版本补上了真正的俯仰锥：扫描结果在发送给客户端**之前**按仰角裁剪。

- 仰角以**雷达视轴**为基准（取载具 pitch，`xRot<0` 表示抬头），所以机头抬起时锥体跟着抬
- 地面车 pitch≈0，等价于"地平线上下各 `verticalSweep` 度"
- **游戏内可调**（1.10.0）：按 `;` 在档位间循环，actionbar 显示 `雷达竖向范围: ±30°`
- **上限也是配置项**（1.10.0）：`radar.verticalSweepMax`，默认 45，最大可到 89

| 配置 | 说明 |
|---|---|
| `radar.verticalSweep` | 默认竖向半角（游戏内切档会覆盖） |
| `radar.verticalSweepMax` | **上限**，默认 45；调到 89 基本消除头顶盲区 |
| `radar.verticalSweepSteps` | 切档用的档位表，默认 `["10","20","30","45"]` |

**关于头顶盲区**：45° 上限下，正头顶目标（仰角 90°）看不到——真实机载雷达也是前向锥体。
现在有两种解法，都只改配置：把 `verticalSweepMax` 提到 60~89；或把档位表里最大那档设成该上限。
（超出上限的档位会被自动过滤，不会出现"切了但没生效"的困惑。）

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

世界目录的 `serverconfig/sbw_radar-server.toml`（开发运行环境可能位于 `run/config/`）。

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
| `radar.verticalSweep` / `verticalSweepMax` | 竖向半角默认值 / 上限（默认 45，可到 89） |
| `radar.verticalSweepSteps` | 竖向切档档位表 |

## 编译

把以下 jar 放入 `libs/`（不随仓库分发）：

`minecraft-neoforge-compiled-*.jar`、`loader-*.jar`、`bus-*.jar`、`modlauncher-*.jar`、
`superbwarfare-*.jar`、`joml`、`gson`、`slf4j-api`、`datafixerupper`、`mergetool-api`

```bash
gradle jar            # 产物在 build/libs/
```

编译 1.11.1 时还需相邻 `../sbw_loadout/build/libs/sbw-loadout-1.3.4.jar` 作为可选接口的 compileOnly 依赖，发布 JAR 不包含挂载代码。若有配套挂载的 NeoForge 开发环境，可复用其已解析依赖，省去准备以上离线依赖：

```powershell
# 在 sbw_loadout 目录，JDK 21
.\gradlew.bat build exportCompileClasspath
.\gradlew.bat -p ..\sbw_radar '-PsharedClasspathFile=../sbw_loadout/build/radar-compile-classpath.txt' build
.\gradlew.bat -Pgametest -PradarCompat runGameTestServer
```

两个模组均可独立运行；同装时最低配套版本为挂载 1.3.4 / 雷达 1.11.1。生产构建完毕应在挂载目录执行不带 gametest、radarCompat 或 smoke 参数的 `clean build`，排除开发夹具。

