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
- **竖向扫描范围**：雷达有了俯仰锥，游戏内按 `;` 切档，上限可配（1.9.0 / 1.10.0）
- **干扰弹内置**：热诱弹走 SBW 车载诱饵池，箔条独立，存在储物空间、HUD 分两行显示（1.11.0）
- **数据链制导**：雷达制导弹通过数据链接收雷达车上行数据；主雷达脱锁时**自动换主**到
  其它能锁定目标的雷达车，直到自身导引头开机（1.12.0）
- **机炮防空**：雷达"锁定"来袭弹药 → 机炮自动换对空弹（近炸）拦截；超出机炮射程改打
  **驾束制导**小导弹——无需锁定，弹沿雷达波束飞向威胁方向（1.13.0）

## 机炮防空（1.13.0 / 1.13.1 手动授权）

所有带机炮的防空车（`Type=AA`、有人驾驶）获得**物理拦截**能力——真弹丸、真弹道、真引信。

**1.13.1 改为手动授权制**：雷达探测到来袭弹药时**只告知不开火**（actionbar 节流警告），
玩家按 `.` 键锁定后才开始拦截，再按一次解除：

```
⚠ 来袭弹药 · 距离 43m · 按锁定键拦截     ← 未锁定时每 30t 提示一次
已锁定来袭弹药 · 43m · 自动拦截开始        ← 按 . 后
拦截中(机炮) · 距离 27m                   ← 锁定期间每秒刷新
目标丢失，已解除锁定                      ← 威胁消失/被击落
```

锁定后系统才做三件事：解提前量（弹速 48块/s 两遍迭代）→ 自动切对空弹
（扫弹药索引找 `small_shell_aa`，找不到保持原弹由近炸兜底）→ `GunData.shoot()` 真实开火。

### 机炮模式（威胁 ≤ `gunRange` 56 格）

1. 每车每 tick 找来袭弹药：探测半径 96 格内、正在逼近、非本车发射的投射物，取最近的一个
2. **解算提前量**：按弹速 48 块/秒两遍迭代算拦截点
3. **自动换弹**：扫描武器的弹药索引，找到 `small_shell_aa`（小口径对空炮弹）就切过去；
   找不到就保持原弹——因为拦截弹都由本类的**近炸引信**兜底（弹进入威胁 3 格内自爆并摧毁威胁）
4. `GunData.shoot()` 真实开火（有弹道、有曳光、有声音），弹丸注册进近炸追踪

### 驾束导弹模式（威胁 > 56 格，≤ `beamSam.maxRange` 192 格）

无需锁定、无需数据链——**驾束制导**：发射一枚小导弹（默认 `superbwarfare:ru_9m336`），
每 tick 把弹的目标点更新为「发射器 → 威胁当前位置」的波束延长线，波束随雷达跟踪实时摆动；
弹进入威胁 4.5 格内起爆。冷却 100 tick，同一威胁同一时刻只分配一枚。

### 配置

| 键 | 说明 |
|---|---|
| `cannonAirDefense.enable` | 总开关 |
| `cannonAirDefense.detectRadius` | 来袭弹药探测半径（96） |
| `cannonAirDefense.notifyInterval` | 未锁定时的警告间隔（30t） |
| `cannonAirDefense.gunRange` | 机炮交战上限（56），超过转驾束导弹 |
| `cannonAirDefense.fireInterval` / `fuzeRadius` | 射击间隔 / 拦截弹近炸半径 |
| `beamSam.enable` / `maxRange` / `cooldown` | 驾束导弹开关 / 射程 / 冷却 |
| `beamSam.fuzeRadius` / `entityId` | 导弹近炸半径 / 使用的导弹实体 |

### 与 AmmoInterceptor 的关系

`AmmoInterceptor`（1.6.x 的抽象拦截）保留且默认关闭 `intercept.vehicles` 列表为空时不起作用——
两者可以共存，但注意别对同一台车同时启用（抽象拦截会先销毁威胁，机炮就没得打了）。

## 数据链制导（1.12.0）

**弹不再依赖"实时无限精度"数据。** 雷达制导弹通过数据链接收任意一台能锁定目标的雷达车
上行数据；主雷达脱锁时自动换主，直到弹自身导引头开机。

三层概念：

| 概念 | 语义 |
|---|---|
| **信道** | 「某台雷达 × 某个目标」的一条链路。**多弹共享一条信道**（半主动语义）；同一目标可被多台雷达各开一条——这就是换主的候选池 |
| **订阅** | 弹 → 信道。弹必须在主雷达的数据链覆盖半径内，飞出去就断链 |
| **链路质量** | `uplinkInterval` 内才允许一次上行；超 `linkTimeout` → DEGRADED（按速度惯性外推）；超 `hardTimeout` → LOST |

### 生命周期

```
发射 → open(雷达,目标) + subscribe(弹,信道)
  ↓ 主雷达持续扫描 → refresh()（受上行间隔节流）
  ↓ 主雷达脱锁 → get() 返回 DEGRADED（惯性外推位置），弹继续按上行数据飞
  ↓ tick() 自动换主：radarsTracking(目标) 里找其它雷达（活/非残骸/在弹覆盖内/配额未满）
  ↓ 进入导引头射程 → acquireTerminal() 开机成功 → unsubscribe() 断链，弹自主飞行
```

- **红外弹天然不参与**：`IrMissileControl` 从不订阅，订阅表里不会有 IR 弹
- 换主候选比"TRK 正锁"宽：TWS 图像里还能看到目标也算"可以锁定"（`radarsTracking`）
- 换主后不做位置平滑——两台雷达测的不是同一个点，弹的位置参考会跳一下（真实系统同样如此）
- 换主冷却 20 tick，防跳台
- 分体式防空（`SplitAirDefense`）发射的弹**自动获得数据链**——它们走同一条 `spawnGuided`

### 配置

| 键 | 说明 |
|---|---|
| `datalink.enable` | 总开关（默认 true） |
| `datalink.uplinkInterval` | 上行间隔（tick）——数据率 |
| `datalink.linkTimeout` / `hardTimeout` | 降级 / 失联阈值（tick） |
| `datalink.maxChannels` | 每台雷达的**信道**上限（不是弹数上限） |
| `datalink.range` | 数据链覆盖半径（块） |
| `datalink.autoHandoff` / `handoffCooldown` | 自动换主开关 / 冷却 |

底层实现：`DatalinkGuidance.java`——只回答 `get()`（弹手里有什么数据），**不转向弹体**；
制导律仍由 `RadarMissileControl` 执行，两者可独立替换。

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
| `radar.verticalSweep` / `verticalSweepMax` | 竖向半角默认值 / 上限（默认 45，可到 89） |
| `radar.verticalSweepSteps` | 竖向切档档位表 |
| `datalink.*` | 数据链制导（见上节） |
| `cannonAirDefense.*` / `beamSam.*` | 机炮防空：拦截 / 驾束导弹 |

## 编译

把以下 jar 放入 `libs/`（不随仓库分发）：

`minecraft-neoforge-compiled-*.jar`、`loader-*.jar`、`bus-*.jar`、`modlauncher-*.jar`、
`superbwarfare-*.jar`、`joml`、`gson`、`slf4j-api`、`datafixerupper`、`mergetool-api`

```bash
gradle jar            # 产物在 build/libs/
```
