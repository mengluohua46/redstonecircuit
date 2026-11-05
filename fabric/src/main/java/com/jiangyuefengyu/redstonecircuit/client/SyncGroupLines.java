package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierFrame;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat;
import com.jiangyuefengyu.redstonecircuit.logic.SyncTorchRule;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 同步火把's group, drawn as lines between the members: one channel is a set of blocks that light together, and
 * nothing in the world says which blocks those are.
 *
 * <p>The geometry half is a pure function of a channel's member list, so "how many lines does a group of n have"
 * and "does the order the server sent them in matter" are answerable without a game - see {@link #lines}. The
 * colour is not carried with them: both ends derive it from the channel number ({@link SyncTorchRule#colour}), so
 * the server never has to say what colour a group is.
 */
@Environment(EnvType.CLIENT)
public final class SyncGroupLines {

    /** 线的半径, 方块单位: 细到不挡住方块, 又粗到隔着几格看得见。 */
    static final float RADIUS = 0.04F;

    /** 两端光点的半径: 方块中心是线的端点, 光点让"从谁出发"一眼看得出。 */
    static final float DOT = 0.09F;

    /** 不透明度, 0-255: 看得清, 又不至于把线后面的地形盖掉。 */
    static final int ALPHA = Math.round(0.85F * 255.0F);

    /**
     * 兜底的门槛, 方块单位: 一个端点所在区块客户端读不到、<b>而且</b>离玩家超过这么远时, 那条世界↔世界的线不画。
     *
     * <p>为什么是 512: 一组的两个成员可以横跨一个基地, 那是看得见的几百格; 而一台机器自己的 plot 坐标离玩家是几万到
     * 几百万格。于是 512 只砍掉"远得没有道理"的那一端 - 它正好是服务器那边判据漏掉一个坐标时, 客户端会画出来的那条
     * 穿过整个世界的线。两个条件必须同时成立: 玩家站在自己那一组旁边时, 区块正在加载, 这道判据一格都不砍。
     *
     * <p>这是兜底, 不是修法: 名单本身不许出现 plot 坐标 (见 {@code SyncGroups#snapshotOf})。
     */
    public static final double FAR_BLOCKS = 512.0;

    /**
     * 世界↔世界那一条支路的兜底判据: 一端画在哪, 它能不能被读到, 以及玩家在哪。
     *
     * <p>纯值, 于是"什么时候跳过一条线"不必跑游戏就能钉住: {@code loaded} 交进来, 玩家坐标交进来。
     * {@link #OPEN} 什么都不跳 - 纯几何的答案 (测试用的就是它), 以及"还没有玩家可问"的那一帧。
     */
    public record WorldGate(Loaded loaded, double x, double y, double z) {

        /** 什么都不跳过。 */
        public static final WorldGate OPEN = new WorldGate(Loaded.ALL, 0.0, 0.0, 0.0);

        /** 这一端要不要跳过: 它所在区块读不到<b>且</b>离玩家比 {@link #FAR_BLOCKS} 还远。 */
        public boolean skips(Spot spot) {
            double dx = spot.x() - this.x;
            double dy = spot.y() - this.y;
            double dz = spot.z() - this.z;
            if (dx * dx + dy * dy + dz * dz <= FAR_BLOCKS * FAR_BLOCKS) {
                return false;
            }
            return !this.loaded.test(BlockPos.containing(spot.x(), spot.y(), spot.z()));
        }
    }

    private SyncGroupLines() {
    }

    /** 一段连线, 两端是各自方块的中心, 世界坐标。 */
    public record Segment(double x1, double y1, double z1, double x2, double y2, double z2) {
    }

    /** 这一格所在的区块现在加载着没有 - 传进来的, 于是下面的几何不必碰世界。 */
    @FunctionalInterface
    public interface Loaded {

        /** True when {@code pos} can be read right now. */
        boolean test(BlockPos pos);

        /** 什么都不检查: 纯几何的答案, 测试用的就是它。 */
        Loaded ALL = pos -> true;
    }

    /**
     * 一个成员, 已经换算到世界: 它的中心在哪, 以及它在机器里时那台机器的位姿。
     *
     * <p>两副坐标各有各的用处: {@code (x, y, z)} 是世界坐标, 跨界的线用它; 位姿栈里的那副由
     * {@link #centreOffset} 给出, 是"机器自己的坐标减去 pivot 再加半格" - 与壳体和内容用的是同一个偏移, 于是
     * 线、壳、器件在同一台机器里共用一个变换。
     *
     * <p>纯值: 没有世界, 没有 {@code PoseStack}, 于是"这条线画在哪"可以被一个不跑游戏的测试钉住。
     */
    public record Spot(@Nullable CarrierId carrier, @Nullable CarrierFrame frame, double x, double y, double z) {

        /** 立在世界里的成员: 中心就是那一格的中心, 没有位姿。 */
        public static Spot inWorld(BlockPos pos) {
            return new Spot(null, null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }

        /** 机器里的成员: 世界中心由那台机器的位姿算出来, 见 {@link CarrierFrame#blockCentre}。 */
        public static Spot carried(CarrierId carrier, CarrierFrame frame) {
            Vector3d world = frame.blockCentre(new Vector3d());
            return new Spot(carrier, frame, world.x, world.y, world.z);
        }

        /** 位姿栈里的中心: {@code (local - pivot) + 0.5}, 两段都在 {@code double} 里算完再收窄。 */
        float[] centreOffset(float[] out) {
            frame.cornerOffset(out);
            out[0] += 0.5F;
            out[1] += 0.5F;
            out[2] += 0.5F;
            return out;
        }
    }

    /**
     * 一条连线, 两副坐标: 世界坐标那副始终有 (跨界的线用它), 位姿栈里那副只在"两端在同一台机器里"时存在。
     *
     * <p>两副都由几何算出来, 于是"走位姿栈还是走世界坐标"是这条线自己的属性, 而不是画线的两端各判一次 - 判一次就够。
     */
    public record Link(Segment world, @Nullable CarrierFrame carrier, @Nullable Segment local) {
    }

    /**
     * 一个频道的全部连线: 全连接, 也就是 n 个成员之间 n(n-1)/2 条。
     *
     * <p>未绑定 ({@link SyncTorchRule#isChannel}) 的频道一个人也没有, 所以这里也答空, 调用方不必自己先判一次。
     * 重复上报的同一格按一格算: 一个位置出现两次会画出一条长度为零、只能在端点看见一个点的线。
     *
     * <p>区块没加载的成员被整个略去, 于是"看不见的那个成员"只让它自己的配对消失, 不会让整组一条线都不画,
     * 也不会因为读一个没加载的坐标而抛。
     *
     * <p>只看世界形态的成员。被机器搬着的那一段走 {@link #links}, 因为那一条配对可能要在位姿栈里画。
     */
    public static List<Segment> lines(int channel, Collection<BlockPos> members, Loaded loaded) {
        List<Spot> spots = new ArrayList<>(members.size());
        for (BlockPos pos : members) {
            if (pos == null || !loaded.test(pos)) {
                continue;
            }
            spots.add(Spot.inWorld(pos));
        }
        List<Segment> out = new ArrayList<>();
        for (Link link : links(channel, spots)) {
            out.add(link.world());
        }
        return out;
    }

    /**
     * 同样的全连接, 但两段成员一起看: 立在世界里的, 和被机器搬着的。
     *
     * <p>三种配对, 两种画法。两端在同一台机器里: 那是{@link Link#local} 非空的一条, 顶点发在那台机器自己的坐标里,
     * 于是整条线跟着机器位姿投影。其余一律用两端各自的世界坐标: 跨界的配对没有一个共有的坐标系, 而两台不同的机器各有
     * 一个位姿, 一条线不能在两个位姿栈里各画一半。
     *
     * <p>未绑定 ({@link SyncTorchRule#isChannel}) 的频道一个人也没有, 所以这里也答空, 调用方不必先判一次。
     * 重复上报的同一格, 以及同一台机器里同一个 local 坐标, 都按一个成员算 - 零长度的线只能在端点看见一个点。
     */
    public static List<Link> links(int channel, Collection<Spot> members) {
        return links(channel, members, WorldGate.OPEN);
    }

    /**
     * 同样的全连接, 外加一道<b>只在世界↔世界那一条支路上</b>生效的兜底.
     *
     * <p>同一台机器的两个成员走位姿栈, 一台机器说了算, 那里没有"很远"这回事; 两条不同的机器、或者一台机器与世界上
     * 的一格, 用的是两端的世界坐标, 于是只有那里可能画出一条穿过整个世界的线 - 一台机器自己的 plot 坐标漏进名单时
     * 正是这一幕。判据见 {@link WorldGate#skips}: 读不到 <b>且</b> 远, 两个条件都成立才跳过, 于是玩家身边那一组
     * 一根线都不少。
     */
    public static List<Link> links(int channel, Collection<Spot> members, WorldGate gate) {
        if (!SyncTorchRule.isChannel(channel)) {
            return List.of();
        }
        // Spot 是纯值: 两个世界成员同一格相等, 同一台机器里同一个 local 也相等, 于是去重不必另有那张表。
        Set<Spot> seen = new HashSet<>();
        List<Spot> centres = new ArrayList<>(members.size());
        for (Spot spot : members) {
            if (spot != null && seen.add(spot)) {
                centres.add(spot);
            }
        }
        int count = centres.size();
        if (count < 2) {
            return List.of();
        }
        List<Link> out = new ArrayList<>(count * (count - 1) / 2);
        for (int i = 0; i < count; i++) {
            Spot from = centres.get(i);
            for (int j = i + 1; j < count; j++) {
                Spot to = centres.get(j);
                Segment world = new Segment(from.x(), from.y(), from.z(), to.x(), to.y(), to.z());
                boolean together = from.carrier() != null && from.carrier().equals(to.carrier());
                if (!together) {
                    if (gate.skips(from) || gate.skips(to)) {
                        // 这一端客户端读不到, 而且在很远的地方: 它在世界坐标里没有一个可信的位置, 于是这一对不画。
                        continue;
                    }
                    out.add(new Link(world, null, null));
                    continue;
                }
                float[] a = from.centreOffset(new float[3]);
                float[] b = to.centreOffset(new float[3]);
                out.add(new Link(world, from.frame(),
                        new Segment(a[0], a[1], a[2], b[0], b[1], b[2])));
            }
        }
        return out;
    }

    /**
     * 把一组频道的线画进世界.
     *
     * <p>单独一层, 于是上面那个纯几何的答案不必加载任何渲染类: 单元测试调 {@link #lines} 时, 这里一个字段都
     * 不会被初始化。
     *
     * <p>世界成员的顶点是世界坐标, 因为世界路径的 pose 栈已经在相机上 ({@code T(-cameraPos)}) - 和这个包里的
     * 壳与内容一样。同一台机器内部的那条线则先进那台机器的位姿栈, 再在它自己的坐标里发顶点, 于是线跟着机器转和缩放。
     * 跨界的线走世界坐标, 它那一端的世界位置由 <b>这一帧的</b>位姿算出来, 与机器此刻画在哪是同一个数。深度不测, 与
     * 眼镜的其他穿透显示一致: 一条被山挡住的线就什么也没说。
     */
    /**
     * <b>一段线画进调用方自己的批次.</b>
     *
     * <p>方块上那个缩微模型里的线走这一条, 而世界里那一趟走 {@link Render}: 两处共用同一份几何 (两片带子加两端光点)
     * 与同一个 {@link #lineType() RenderType}, 于是"组线"是同一件东西画在两个坐标系里, 而不是两套迟早不一致的实现。
     * 坐标由调用方给: 调用方站在哪个坐标系里, 这里就只负责画。
     */
    static void emitSegment(PoseStack.Pose pose, VertexConsumer buffer, Segment segment, int argb) {
        Render.emit(pose, buffer, new float[12], segment, argb);
    }

    /** 线用的那个 RenderType: 世界里与方块上画的线因此是同一批状态 (半透明, 不测深度, 不剔除背面)。 */
    static RenderType lineType() {
        return Render.TYPE;
    }

    /** 一个频道的颜色, 连不透明度一起: 两端都从频道号算出来, 服务器不必说颜色。 */
    static int argbOf(int channel) {
        return (SyncTorchRule.colour(channel) & 0x00FFFFFF) | (ALPHA << 24);
    }

    /**
     * <b>一个成员这一眼看不看得见</b> - 交进来的, 于是"整组都被挡住就不画"这条判据不必跑游戏就能钉住。
     *
     * <p>世界那一趟按视线打一条射线 ({@code Render#sightOf}), 方块上那个小模型那一趟问它的方块
     * ({@link #seesBlock})。纯几何那一层 ({@link #links}) 不问这件事: 一条线画在哪与看不看得见是两件事。
     */
    public interface Visible {
        /** 这一点的中心此刻看得见吗. */
        boolean test(double x, double y, double z);

        /** 都看得见: 没有判据时的答案 (测试用, 也是问不到玩家那一帧的答案)。 */
        Visible ALL = (x, y, z) -> true;
    }

    /**
     * <b>一组成员里有没有一个露在外面.</b>
     *
     * <p>为什么按组判而不是按对判: 一条线说的是"这两支是一组", 而这句话只有在一个成员真的露在外面时才有意义。
     * 整组都在墙后面时, 画出来的线像是在墙上开了一个洞 - 玩家既看不出是谁连给谁, 也看不出那台机器在哪。
     * 所以判据只有一句: <b>整组都被挡住就整组不画</b>; 只要有一个露着, 通向被挡住那几支的线照旧画, 那正是
     * "看见一支, 就知道它连着谁"。
     */
    public static boolean anyVisible(Collection<Spot> spots, Visible visible) {
        for (Spot spot : spots) {
            if (spot != null && visible.test(spot.x(), spot.y(), spot.z())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一个方块这一眼看得见吗 - 与组线同一条射线, 于是方块上那个小模型里的线同样不穿墙。
     */
    public static boolean seesBlock(ClientLevel level, BlockPos pos) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return true;
        }
        return Render.sees(level, player.getEyePosition(),
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    static final class Render {

        /** 名字带 modid: 每个模组的批次都要有自己的名字。 */
        private static final RenderType TYPE = RenderType.create(
                RedstoneCircuit.MODID + "_sync_group_lines",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(false));

        private Render() {
        }

        /**
         * 每个频道的成员都取自客户端自己那份名单, 每个频道的颜色都由频道号算出来。
         *
         * <p>世界成员和搬运成员在同一个批次里: 位姿栈的 push/pop 只影响之后写进去的顶点, 而顶点在写进去的那一刻
         * 就已经带着矩阵, 所以两段不必分成两个缓冲。
         */
        static void draw(PoseStack pose, ClientLevel level, Collection<Integer> channels, float partialTick) {
            // 兜底判据一帧一份: 玩家的位置这一帧不变, 于是"谁离得太远"不必每对线各算一次。
            WorldGate gate = gateOf(level);
            // 遮挡判据同样一帧一份: 眼睛这一帧不动, 而射线是这条路径上最贵的一步。
            Visible sight = sightOf(level);
            Map<Integer, List<Link>> groups = new LinkedHashMap<>();
            for (int channel : channels) {
                List<Spot> members = spots(level, channel, partialTick,
                        SyncGroupView.membersOf(level.dimension(), channel), level::hasChunkAt);
                // 整组都被挡住: 一条都不画 (判据只有一处, 见 anyVisible)。
                if (!anyVisible(members, sight)) {
                    continue;
                }
                List<Link> found = links(channel, members, gate);
                if (!found.isEmpty()) {
                    groups.put(channel, found);
                }
            }
            if (groups.isEmpty()) {
                return;
            }
            MultiBufferSource.BufferSource source =
                    MultiBufferSource.immediate(new ByteBufferBuilder(1536));
            VertexConsumer buffer = source.getBuffer(TYPE);
            float[] frame = new float[12];
            for (Map.Entry<Integer, List<Link>> group : groups.entrySet()) {
                int colour = SyncTorchRule.colour(group.getKey());
                int argb = (colour & 0x00FFFFFF) | (ALPHA << 24);
                for (Link link : group.getValue()) {
                    if (link.local() == null) {
                        emit(pose.last(), buffer, frame, link.world(), argb);
                        continue;
                    }
                    // 同一台机器内部的一条: 先进那台机器的位姿栈, 顶点之后才发 - 顺序就是"跟着机器走"本身。
                    pose.pushPose();
                    HostRenderPass.applyCarrierFrame(pose, link.carrier());
                    emit(pose.last(), buffer, frame, link.local(), argb);
                    pose.popPose();
                }
            }
            source.endBatch(TYPE);
        }

        /**
         * 一个频道的两段成员, 都换算到世界。
         *
         * <p>世界那一段先过 {@code loaded}: 区块没读进来的坐标不去碰, 于是它只让自己那几对消失, 不会让整组一条线
         * 都不画, 也不会因为读一个没加载的坐标而抛。
         *
         * <p>搬运那一段先按机器分组: 一帧里同一台机器可能带着好几个成员, 位姿每帧只该问一次。拿不到那台机器的位姿
         * 就整台跳过 - 一对线宁可不在, 也不要画在一个猜出来的位置上。
         */
        private static List<Spot> spots(ClientLevel level, int channel, float partialTick,
                                        Collection<BlockPos> world, Loaded loaded) {
            List<Spot> spots = new ArrayList<>(world.size());
            for (BlockPos pos : world) {
                if (pos != null && loaded.test(pos)) {
                    spots.add(Spot.inWorld(pos));
                }
            }
            Map<CarrierId, List<BlockPos>> byCarrier = new LinkedHashMap<>();
            for (RCNetwork.SyncGroupPayload.CarriedMember member
                    : SyncGroupView.carriedOf(level.dimension(), channel)) {
                byCarrier.computeIfAbsent(member.carrier(), id -> new ArrayList<>()).add(member.local());
            }
            for (Map.Entry<CarrierId, List<BlockPos>> entry : byCarrier.entrySet()) {
                CarrierPose pose = poseOf(level, entry.getKey(), partialTick);
                if (pose == null) {
                    continue;
                }
                for (BlockPos local : entry.getValue()) {
                    spots.add(Spot.carried(entry.getKey(), CarrierFrame.forBlock(pose,
                            new Vector3d(local.getX(), local.getY(), local.getZ()))));
                }
            }
            return spots;
        }

        /**
         * 那台机器这一帧的位姿: Sable 自己画的那个优先, 否则网络送到的那一份, 两者都没有就是 {@code null}。
         *
         * <p>与搬运内容同一个顺序 (见 {@code HostRenderPass#collectCarried}): 线说的"机器在哪"和壳体画的"机器在哪"
         * 必须是同一个数, 否则线会从壳身边擦过去。位姿过期同样算"没有": 那台机器这一帧已经不画了, 一条线不该留在原地。
         */
        @Nullable
        private static CarrierPose poseOf(ClientLevel level, CarrierId carrier, float partialTick) {
            CarrierPose pose = PhysicsCompat.renderPoseOf(level, carrier, partialTick);
            if (pose == null) {
                pose = ClientHostCache.carrierPose(carrier, partialTick);
            }
            if (pose == null || ClientHostCache.carrierPoseAge(carrier) > ClientHostCache.staleTicks()) {
                return null;
            }
            return pose;
        }

        /**
         * 这一帧的兜底判据: 玩家的眼睛在哪, 以及某个坐标这一刻读不读得到.
         *
         * <p>没有玩家可问时 (菜单, 加载中) 答 {@link WorldGate#OPEN}: 那一帧没有"离玩家多远"这个概念, 而宁可让
         * 名单自己说话, 也不要因为问不到玩家就把别人的线全砍掉。
         */
        /**
         * 世界那一趟的遮挡判据: 从眼睛到这一格中心打一条射线.
         *
         * <p>命中了自己的那一格算看得见 (火把自己的碰撞箱就在那一格里), 什么都没命中同样算 - 于是这条判据
         * 只在一件事上说"不": 前面挡着别的方块。玻璃这类透明而实心的方块同样算挡住, 因为玩家确实看不见
         * 它后面那一格。
         *
         * <p>每帧每个成员一条射线: 一个位置只属于一个频道, 于是总条数就是这一帧要画的成员数, 与频道数无关。
         */
        private static Visible sightOf(ClientLevel level) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                return Visible.ALL;
            }
            Vec3 eye = player.getEyePosition();
            return (x, y, z) -> sees(level, eye, x, y, z);
        }

        /** 一条射线: 从 {@code eye} 到这一点, 中间有没有别的方块。 */
        private static boolean sees(ClientLevel level, Vec3 eye, double x, double y, double z) {
            BlockPos target = BlockPos.containing(x, y, z);
            BlockHitResult hit = level.clip(new ClipContext(eye, new Vec3(x, y, z),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, Minecraft.getInstance().player));
            return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
        }

        private static WorldGate gateOf(ClientLevel level) {
            LocalPlayer player = Minecraft.getInstance().player;
            return player == null
                    ? WorldGate.OPEN
                    : new WorldGate(level::hasChunkAt, player.getX(), player.getY(), player.getZ());
        }

        /** 一段线加两端的光点; 两片互相垂直的带子, 于是顺着其中一片看过去时线不会整个消失。 */
        private static void emit(PoseStack.Pose pose, VertexConsumer buffer, float[] frame, Segment segment,
                                 int argb) {
            float x1 = (float) segment.x1();
            float y1 = (float) segment.y1();
            float z1 = (float) segment.z1();
            float x2 = (float) segment.x2();
            float y2 = (float) segment.y2();
            float z2 = (float) segment.z2();
            float dx = x2 - x1;
            float dy = y2 - y1;
            float dz = z2 - z1;
            float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length <= 0.0F) {
                return;
            }
            float[] u = new float[3];
            perpendicular(dx / length, dy / length, dz / length, u);
            float[] v = {
                    u[1] * dz / length - u[2] * dy / length,
                    u[2] * dx / length - u[0] * dz / length,
                    u[0] * dy / length - u[1] * dx / length,
            };
            float cx = (x1 + x2) / 2.0F;
            float cy = (y1 + y2) / 2.0F;
            float cz = (z1 + z2) / 2.0F;
            ribbon(pose, buffer, frame, argb, cx, cy, cz, dx / 2.0F, dy / 2.0F, dz / 2.0F, u);
            ribbon(pose, buffer, frame, argb, cx, cy, cz, dx / 2.0F, dy / 2.0F, dz / 2.0F, v);
            dot(pose, buffer, frame, argb, x1, y1, z1);
            dot(pose, buffer, frame, argb, x2, y2, z2);
        }

        private static void ribbon(PoseStack.Pose pose, VertexConsumer buffer, float[] frame, int argb,
                                   float cx, float cy, float cz, float hx, float hy, float hz,
                                   float[] across) {
            square(pose, buffer, frame, argb, cx, cy, cz,
                    across[0] * RADIUS, across[1] * RADIUS, across[2] * RADIUS, hx, hy, hz);
        }

        /** 三片互相垂直的方块片: 从一个点看过去就是一个小亮点。 */
        private static void dot(PoseStack.Pose pose, VertexConsumer buffer, float[] frame, int argb,
                                float x, float y, float z) {
            square(pose, buffer, frame, argb, x, y, z, DOT, 0.0F, 0.0F, 0.0F, DOT, 0.0F);
            square(pose, buffer, frame, argb, x, y, z, 0.0F, DOT, 0.0F, 0.0F, 0.0F, DOT);
            square(pose, buffer, frame, argb, x, y, z, DOT, 0.0F, 0.0F, 0.0F, 0.0F, DOT);
        }

        /** 一个以 {@code (cx, cy, cz)} 为中心、两个半轴为 {@code a} 与 {@code b} 的四边形。 */
        private static void square(PoseStack.Pose pose, VertexConsumer buffer, float[] frame, int argb,
                                   float cx, float cy, float cz,
                                   float ax, float ay, float az, float bx, float by, float bz) {
            corner(frame, 0, cx - ax - bx, cy - ay - by, cz - az - bz);
            corner(frame, 3, cx + ax - bx, cy + ay - by, cz + az - bz);
            corner(frame, 6, cx + ax + bx, cy + ay + by, cz + az + bz);
            corner(frame, 9, cx - ax + bx, cy - ay + by, cz - az + bz);
            for (int index = 0; index < 4; index++) {
                buffer.addVertex(pose, frame[index * 3], frame[index * 3 + 1], frame[index * 3 + 2])
                        .setColor(argb);
            }
        }

        private static void corner(float[] frame, int at, float x, float y, float z) {
            frame[at] = x;
            frame[at + 1] = y;
            frame[at + 2] = z;
        }

        /**
         * 一个与给定方向垂直的单位向量: 与"分量最小的那个坐标轴"叉积, 那个轴一定不平行于方向, 所以不必为
         * "方向正好落在某个轴上"写分类讨论。
         */
        private static void perpendicular(float x, float y, float z, float[] out) {
            float ax = Math.abs(x);
            float ay = Math.abs(y);
            float az = Math.abs(z);
            float hx = ax <= ay && ax <= az ? 1.0F : 0.0F;
            float hy = hx == 0.0F && ay <= az ? 1.0F : 0.0F;
            float hz = hx == 0.0F && hy == 0.0F ? 1.0F : 0.0F;
            float cx = y * hz - z * hy;
            float cy = z * hx - x * hz;
            float cz = x * hy - y * hx;
            float length = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            out[0] = cx / length;
            out[1] = cy / length;
            out[2] = cz / length;
        }
    }
}
