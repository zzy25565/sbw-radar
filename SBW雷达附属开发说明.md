# SBW 雷达附属（sbw_radar）开发说明

> 版本：1.1.2 · 2026-10-22
> 目标环境：Minecraft 1.21.1 / NeoForge 21.1.249 / 卓越前线 0.8.9.1~0.8.9.2
> 服务器与客户端必须同版本（本模组注册了自建网络通道，版本不一致会拒绝连接）

---

## 一、功能总览

| 模块 | 说明 |
|---|---|
| 注入雷达 | 给原生 AA 载具与飞机/直升机注入雷达（走 SBW 自己的 `RadarScanner`，因此对第三方附属载具同样生效） |
| 三种模式 | SRC 搜索 / TWS 搜索兼跟踪 / TRK 单目标锁定 |
| 定向天线 | 有向天线（山毛榉、S-400 发射车）只能在前向扇区内扫，需整车转向 |
| 扫描宽度 | 30° / 60° / 120° 可切（`L`） |
| 机载雷达 | 飞机与直升机自带机头雷达，前向扇区、320 格 |
| 雷达制导导弹 | 半主动+末端主动（ARH）：中制导靠雷达喂目标，末端弹上导引头接管 |
| 红外导弹 | 飞机/直升机专用，热源锁定、发射前锁定音、可被诱饵干扰 |
| 战术地图 | 覆盖圈 + 目标情报（飞机单发/双发、生物血量分档） |
| 数据驱动 | 弹药表、雷达档案、目标情报全部是 JSON，改数据即可，不用改代码 |

---

## 二、键位

| 键 | 作用 | 备注 |
|---|---|---|
| `K` | 循环雷达模式 SRC → TWS → TRK | 无锁定时 TRK 会尝试自动捕获最近目标 |
| `J` | TRK 下切换锁定目标 | 仅在 TRK 有效 |
| `L` | 循环扫描宽度 30/60/120 | 只影响扇扫，TRK 固定 8° 窄波束 |

> `B` 留给 ZZY 载具的发射演出、`R` 留给 SBW 换弹，避免冲突。

---

## 三、弹药配置（本附属自带，数据驱动）

**文件**：`data/sbw_radar/ammo/specs.json`
**代码**：`AmmoSpecs.java`

武器开火时，附属按**实际打的弹丸 id** 去表里查（先精确匹配，再按路径后缀匹配），从而决定「这发弹归谁管、怎么导」。

```json
{
  "ammo": {
    "superbwarfare:ru_9m336": {
      "category": "RADAR",
      "missileEntity": "superbwarfare:ru_9m336",
      "seekerRange": 96,
      "climbTicks": 12,
      "climbUpDeg": 10,
      "maxPerRadar": 2
    },
    "superbwarfare:fim_92_missile": {
      "category": "IR",
      "missileEntity": "superbwarfare:fim_92_missile",
      "seekerRange": 64,
      "climbTicks": 6,
      "climbUpDeg": 4,
      "flareChance": 0.6,
      "requireLock": true
    }
  }
}
```

### 字段说明

| 字段 | 适用 | 含义 |
|---|---|---|
| `category` | 全部 | `RADAR` 走雷达制导，`IR` 走热寻的；**没写默认 RADAR** |
| `missileEntity` | 全部 | 实际生成的弹体 id（渲染/音效/爆炸沿用 SBW 的实体） |
| `seekerRange` | 全部 | 末端导引头接管距离（格） |
| `climbTicks` | 全部 | 出筒后沿照射轴飞行的 tick 数 |
| `climbUpDeg` | 全部 | 照射轴的上偏角（垂直冷发射调大、机载弹调小） |
| `maxPerRadar` | RADAR | 每台雷达同时制导的弹数（照射通道） |
| `flareChance` | IR | 诱饵干扰权重（每次判定再乘 0.2） |
| `requireLock` | IR | 是否必须有锁定音才能发射 |

**改弹种不需要改代码**：加一条 JSON 就能让新弹药接入；`missileEntity` 留空则用服务器配置里的默认弹体。

---

## 四、服务器配置（`config/sbw_radar-server.toml`）

### 雷达

| 键 | 默认 | 说明 |
|---|---|---|
| `radar.range` | 512 | 地面雷达探测距离 |
| `radar.sweepAngle` | 120 | 全向车默认扇角 |
| `radar.rotationSpeed` | 1.5 | 扫描转速（度/tick） |
| `radar.minTargetHeight` / `maxTargetHeight` | 8 / 400 | 高度门限（默认只扫空中） |
| `radar.scanInterval` | 5 | 扫描间隔 |
| `radar.widthSteps` | 30,60,120 | `L` 键循环的档位 |
| `radar.perTickVehicles` | buk_m3, 625e | 每 tick 刷新的车（天线跟随） |
| `radar.shareWithTeammates` | true | 共享给队友 |

### 雷达制导导弹

| 键 | 默认 | 说明 |
|---|---|---|
| `missile.enable` | true | 总开关 |
| `missile.vehicleId`（`missile.vehicles` 列表） | 山毛榉M3/625E/通古斯卡/S-400/VLS | 白名单，按 `namespace:path` |
| `missile.ammoKeywords` | missile,rocket,agm,aam | 判定「是不是导弹类弹药」 |
| `missile.maxConcurrent` | 2 | 通道数（弹药表可覆盖） |
| `missile.climbTicks` / `climbUpDeg` | 12 / 10 | 爬升段参数（弹药表可覆盖） |
| `missile.seekerRange` | 96 | 末端交班距离（弹药表可覆盖） |
| `missile.lockLostTimeout` | 120 | 彻底丢目标后自毁的 tick 数 |
| `missile.entityId` | superbwarfare:ru_9m336 | 默认弹体 |

### 红外导弹

| 键 | 默认 | 说明 |
|---|---|---|
| `ir.enable` | true | 空对空红外总开关 |
| `ir.entityId` | superbwarfare:fim_92_missile | 默认弹体 |
| `ir.seekerCone` | 30 | 导引头半锥角（度） |
| `ir.rangeRear` | 320 | 尾追锁定距离（热排气可见） |
| `ir.rangeFront` | 120 | 迎头锁定距离（热信号小） |
| `ir.requireLock` | true | 必须有锁定音才能射 |
| `ir.flareChance` | 0.6 | 诱饵干扰权重 |

---

## 五、其它数据文件

| 文件 | 作用 | 关键字段 |
|---|---|---|
| `assets/sbw_radar/radar_profiles.json` | 每台车的雷达档案 | `Omni`(是否全向)、`Sector`(扇角)、`SrcInterval`/`TwsInterval`/`TrkInterval`、`Modes` |
| `assets/sbw_radar/target_intel.json` | 战术地图目标情报 | `vehicles.<entityPath> = 发动机数量`（1 单发 / 2 双发 / ≥3 多发） |

---

## 六、制导逻辑

### 三段式（雷达弹）

```
① 爬升段（climbTicks）
   方向 = 雷达车→目标 的照射轴 + climbUpDeg 上偏
   期间完全不看目标，像被波束"推"出去
        ↓
② 中制导
   每 tick 从 ServerRadarHandler 取 TRK 锁定目标，喂给弹体
   （SBW 的 MissileProjectile 用 setTargetUuid / setTargetVec 接收）
   雷达脱锁 → 飞向最后已知点
        ↓
③ 末端（seekerRange 内或末端捕获成功）
   弹上导引头接管：35° 半锥、按速度方向搜索非友方目标
   此后与发射雷达彻底脱钩（雷达被毁也不影响）
```

### 照射通道
`maxPerRadar` 限制同时制导数。超出的发射请求直接拒绝——群发要有代价。

### 红外弹
- 发射前：服务端每 5 tick 为骑乘飞机/直升机的玩家刷新锁定（锥角 + 迎头/尾追距离差 + 队伍过滤），下发 `IrState` 包。
- 客户端：锁定时每 10 tick 播一次锁定音（`NOTE_BLOCK_PLING`，音高 1.9），HUD 显示 `IR LOCK xxxm`。
- 发射后：弹上追实体；24 格内的 `FlareDecoyEntity` 按 `flareChance×0.2` 概率触发 `setDistracted(true)`。

---

## 七、网络与状态

| 包 | 方向 | 用途 |
|---|---|---|
| `sbw_radar:cycle_mode` | C→S | 切模式 |
| `sbw_radar:cycle_target` | C→S | 切锁定目标 |
| `sbw_radar:cycle_width` | C→S | 切扫描宽度 |
| `sbw_radar:state` | S→C | 模式/目标/跟踪数/宽度（HUD 用） |
| `sbw_radar:ir_state` | S→C | 红外锁定状态（锁定音用） |

服务端权威：所有模式、锁定、通道判断都在服务端，客户端只负责显示与发意图。

---

## 八、源码结构

| 文件 | 职责 |
|---|---|
| `SbwRadarMod.java` | 模组入口，注册事件总线（含客户端分支） |
| `SbwRadarConfig.java` / `SbwRadarClientConfig.java` | 服务端 / 客户端配置 |
| `ServerRadarHandler.java` | 雷达状态机：模式、扫描、照射、通道、目标跟踪 |
| `RadarProfiles.java` | 读取 `radar_profiles.json`（含机载档案） |
| `RadarApi.java` | 第三方雷达源接入点 |
| `RadarMissileControl.java` | 雷达制导导弹：发射、爬升、中制导、末端、脱锁 |
| `IrMissileControl.java` | 红外弹：锁定刷新、发射、诱饵干扰 |
| `AmmoSpecs.java` | 读取 `ammo/specs.json`，提供弹药→制导参数映射 |
| `RadarPayloads.java` / `RadarNetwork.java` | 网络包定义与注册 |
| `ClientRadarState.java` / `ClientIrState.java` | 客户端状态镜像 |
| `ClientInput.java` | 键位与输入 |
| `ClientRenderers.java` | 雷达 HUD、锁定指示 |
| `TacticalMapOverlay.java` | 战术地图叠加（覆盖圈 + 目标情报） |
| `TargetIntel.java` | 发动机数 / 血量分档 |

---

## 九、编译与部署

本机走**离线 javac 链路**（不依赖 gradle 联网）：

```bash
cd sbw_radar
gradle jar          # 若 gradle 缓存齐全
# 或
javac @build/args.txt
```

依赖坑（都踩过）：
- `FriendlyByteBuf`（不是 `ByteBuf`）用于 varint；
- 需 `netty-buffer`、`netty-common`、`brigadier`、`lwjgl-glfw`、`gson`、`datafixerupper`、`mergetool-api`（`Dist`）、`logging`（`LogUtils`）；
- `Level` 没有 `getEntity(UUID)`，必须 cast 成 `ServerLevel`。

部署：jar 丢 `mods/`，**服务端与客户端同版**；改 `specs.json` 后重启服务器生效。

---

## 十、已知限制 / 路线图

| 项 | 状态 |
|---|---|
| HUD 显示剩余照射通道 | 未做（`activeFor()` 已就绪） |
| 导弹尾迹/音效定制 | 沿用 SBW 弹体，未自定义 |
| TWS 多目标同时制导 | 目前制导跟 TRK 单目标，TWS 只影响显示刷新率 |
| 红外弹 maddog（无锁发射） | 通过 `requireLock=false` 可开 |
| 雷达告警接收机（RWR） | 未做 |
| 干扰弹主动投放 | 依赖载具自带，未做专属按键 |
