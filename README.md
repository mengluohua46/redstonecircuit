# 红石电路 · Redstone Circuit

把红石元件放进**方块内部**，再用**红石眼镜**看见它们。

Put redstone components *inside* blocks, then see them with the redstone goggles.

* Minecraft **1.21.1** · **NeoForge 21.1.250** · required on both client and server
* 作者 / Author: 江月风雨

---

## 这是什么 / What it is

原版红石只能铺在地面上，占地方也不好看。本模组让红石、中继器、比较器等元件可以**存进方块内部**
（石头、木板……任何完整的不透明方块），并从那个方块**照常向外发出红石信号**；戴上红石眼镜就能
看见方块里面的线路、朝向、档位与连接关系。

Vanilla redstone has to lie on the ground. This mod lets dust, repeaters, comparators and the rest be
stored **inside** a block, while that block still emits redstone exactly as the component would - and
the redstone goggles let you see the wiring, facing, delay and connections inside it.

## 内容 / Features

| 内容 | 说明 |
|---|---|
| 方块内元件 | 红石粉、中继器、比较器、红石火把、拉杆、按钮都可以 Shift+右键放进方块内部；空手 Shift+右键取回 |
| 与原版一致 | 不修改任何原版红石特性：发光方向、强弱电、延迟、衰减全部照原版规则 |
| 垂直面 | 元件也可以贴在方块的顶面/底面，连接规则与平地相同 |
| 红石眼镜 | 戴在头上（无护甲值）后，含红石的方块变半透明并显示内部元件；脱下立刻恢复 |
| 红石扳手 | 右键选中一个元件，Shift+右键相邻方块锁定这条线，其余方向自动隔断；可锁定多条、也可锁定活塞/红石灯等没有嵌入红石的方块 |
| 超导红石粉 | 铜锭居中 + 8 个红石 → 9 个。与红石粉完全一样，但**信号不衰减**，多远都保持原强度；右键铺在方块上，Shift+右键放进方块内部 |
| 空手调设置 | 空手右键中继器切换延迟档位、右键比较器切换比较/减法模式（和原版右键地面上的元件一样） |
| 无侵入 | 原版方块、原版世界数据都不改动；数据存在世界存档的自定义数据里 |

## 安装 / Installation

1. 安装 **NeoForge 21.1.250**（或同 1.21.1 版本线的更新版本）用于 Minecraft **1.21.1**。
2. 把 `redstonecircuit-1.0.0.jar` 放进 `.minecraft/mods`（客户端与服务端都要放）。
3. 启动游戏即可，没有其它前置模组。

## 合成 / Recipes

* **红石眼镜**：`玻璃 · 红石 · 玻璃`（横排三格）
* **红石扳手**：`红石` 竖着三个
* **超导红石粉**：3×3 里正中间放**铜锭**、周围一圈 8 个**红石** → 产出 **9 个**

## 用法 / Usage

* **放进方块**：手持元件，**Shift+右键**目标方块（点哪一面，那一面就是它的输入面）。
* **取回**：空手 **Shift+右键**该方块。
* **操作开关/设置**：空手**右键**含拉杆、按钮、中继器、比较器的方块。
* **改线路**：手持**红石扳手**右键一个元件选中，再 **Shift+右键**相邻的方块锁定连接；
  重复同样操作依次切换 `锁定 → 断开 → 自动`；Shift+右键已选中的方块本身可清除它的全部设定。
  （需要佩戴红石眼镜）

## 配置 / Configuration

`config/redstonecircuit-common.toml`：

| 键 | 默认 | 说明 |
|---|---|---|
| `hostOverlayMode` | `GOGGLES` | `GOGGLES` 只在戴眼镜时显示内部；`ALWAYS` 不用眼镜也能看；`OFF` 完全不画 |
| `hostOverlayAlpha` / `Color` / `Frame*` | 150 / 淡蓝 / 红 | 外壳与边框的外观 |
| `showInnerComponents` | `true` | 是否绘制方块内部的元件 |
| `seeInnerComponentsThroughWalls` | `false` | 关掉视线检测（真透视） |
| `wrenchNeedsGoggles` | `true` | 扳手是否必须先戴眼镜 |
| `hostAcceptsStrongPower` | `true` | 是否接受旁边实心方块传导的强电（原版语义） |
| `slotSyncRange` | `0` | 元件数据同步范围；0 = 本维度所有玩家 |
| `debugLog` | `true` | 详细日志；嫌刷屏可以改 `false` |

## 反馈 / Reporting problems

出问题时请附上 `logs/latest.log`（本模组的行以 `[redstonecircuit]` 开头，其中包含方块状态、
供电来源与崩溃堆栈），以及你当时在做什么操作。

## 版权 / License

**All Rights Reserved**，详见 [LICENSE](LICENSE)。整合包可以原样收录并署名；
修改后再分发或商业使用需要事先许可。

**All Rights Reserved** - see [LICENSE](LICENSE). Modpacks may include it unmodified with credit;
redistributing modified copies or using it commercially needs permission first.

`TEMPLATE_LICENSE.txt` 是 NeoForged MDK 模板文件自身的 MIT 许可，与本模组的授权无关。

## 说明 / Notes

* 超导线与超导粉的方块贴图是原版红石粉贴图的**换色版本**（橙色），红石眼镜的头顶贴图由原版皮革头盔
  贴图改色而来；其余贴图（眼镜、扳手、图标）为本模组自制。如果你对这类衍生资源有顾虑，
  可以自行替换 `src/main/resources/assets/redstonecircuit/textures/` 下的对应文件。
* 模组通过 Mixin 让原版方块能够"用内部元件的信号对外发光"，这是 NeoForge 目前唯一可行的方式；
  原版逻辑本身没有被覆盖（只做提前返回的注入）。
