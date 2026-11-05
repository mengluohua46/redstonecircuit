package com.jiangyuefengyu.redstonecircuit.blueprint;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

/**
 * The Create side of placement: four answers turned into calls on {@link BlueprintRestore}.
 *
 * <p>Every entry point here is reached from inside Create's own code and names no Create type - the classes, the
 * settings accessor and the item components are all looked up by name at most once. That is what lets the hooks be
 * absent: a Create update that renames any of it makes these methods no-ops, so a blueprint still places, exactly as
 * it did before this mod existed, and one line in the log says why its components did not come with it.
 *
 * <p>The blueprint file is not read twice. Create reads it once, and the one place that sees the whole tag is
 * {@code StructureTemplate#load}; the section is held from there until the load that follows hands it, together with
 * the item's rotation, mirror and anchor, to {@link BlueprintRestore}.
 */
public final class BlueprintDeploy {

    private static final String CREATE_SCHEMATIC_ITEM = "com.simibubi.create.content.schematics.SchematicItem";
    private static final String CREATE_DATA_COMPONENTS = "com.simibubi.create.AllDataComponents";

    /** How long a section may wait for the load that follows it; the two happen inside one call, on one thread. */
    private static final ThreadLocal<CompoundTag> SECTION = new ThreadLocal<>();

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private BlueprintDeploy() {
    }

    /**
     * One line about one deploy, behind {@code debugLog}.
     *
     * <p>Added because "the group line was not drawn" cannot be told apart from "the channel never arrived" without
     * knowing which gate the deploy stopped at: the section may be missing from the file, Create's settings may not
     * be readable, or the section may simply carry no channels. One real run answers all three.
     */
    private static void trace(String message) {
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] " + message);
        }
    }

    // ------------------------------------------------------------ read side --

    /**
     * One blueprint file, as Create read it. Anything that is not one of ours is left out of the way - including the
     * absence of a section in a file that has one, which must not leave a previous file's section behind.
     */
    public static void sectionRead(@Nullable CompoundTag tag) {
        if (tag != null && tag.contains(BlueprintComponents.KEY)) {
            SECTION.set(tag);
            trace("blueprint file carries our section");
        } else {
            SECTION.remove();
            trace("blueprint file carries NO redstonecircuit section (tag=" + (tag == null ? "null" : "present")
                    + ") - nothing of ours can be restored from it");
        }
    }

    /**
     * The blueprint has been loaded: everything it carries is turned into world coordinates now, once.
     *
     * <p>Rotation and mirror come from the item, never from the file, and the anchor is the item's own component -
     * the same value Create's printer reads, so the two cannot disagree about where the blueprint is.
     */
    public static void afterSchematicLoad(@Nullable Level level, @Nullable ItemStack stack) {
        CompoundTag tag = SECTION.get();
        SECTION.remove();
        if (tag == null || !(level instanceof ServerLevel serverLevel) || stack == null || stack.isEmpty()) {
            trace("blueprint deploy: skipped before reading it (section=" + (tag == null ? "null" : "present")
                    + ", level=" + (level instanceof ServerLevel ? "server" : "not-server")
                    + ", stack=" + (stack == null || stack.isEmpty() ? "empty" : "present") + ")");
            return;
        }
        try {
            StructurePlaceSettings settings = settingsOf(stack);
            BlockPos anchor = anchorOf(stack);
            if (settings == null || anchor == null) {
                trace("blueprint deploy: skipped (settings=" + (settings == null ? "null" : "ok")
                        + ", anchor=" + (anchor == null ? "null" : "ok") + ") - Create's components/reflection"
                        + " could not be read, so nothing of ours was restored");
                return;
            }
            // 一次放置一张新表: 上一次为另一台机器领的号不该被这一次接着用.
            BlueprintRestore.forgetChannels(serverLevel.dimension());
            BlueprintPlacement.Budget budget = budgetOf(tag, anchor, settings.getRotation(), settings.getMirror());
            if (budget != null) {
                BlueprintRestore.remember(serverLevel.dimension(), anchor, budget);
                restoreSyncTorchChannels(serverLevel, tag, anchor, settings);
            } else {
                trace("blueprint deploy: the section decoded to no budget, so nothing of ours was placed");
            }
        } catch (RuntimeException failed) {
            report("load", "a blueprint's components could not be read: " + failed);
        }
    }

    /**
     * 世界形态的同步火把的频道, 写回它们要落地的格子。
     *
     * <p>为什么不走 {@link BlueprintRestore}: 那张表是"坐标 → 元件", 而这里的频道行不是元件 - 它属于 store 的位置表,
     * 与时钟源的设置同一类东西。落点由 {@link BlueprintPlacement#toWorld} 换算, 因为蓝图自己的坐标与世界的坐标只该在
     * 那一处相遇; 旋转与镜像从物品上来, 与元件的落点用的是同一对值, 所以一支火把和它旁边那个方块不会转到两个地方去。
     *
     * <p>写在<b>加载</b>这一刻而不是每个方块落地时: 频道行不依赖方块是否已经在那里 (组 pass 会跳过还立着空气的格子),
     * 而 Create 的一次打印可能跨越很多 tick。
     */
    private static void restoreSyncTorchChannels(ServerLevel level, CompoundTag tag, BlockPos anchor,
                                                 StructurePlaceSettings settings) {
        BlueprintComponents.Section section = BlueprintComponents.readFile(tag);
        if (section == null || section.syncTorches().isEmpty()) {
            trace("blueprint deploy: section has NO standing sync torch channel (section=" + (section == null ? "damaged" : "ok")
                    + ") - any torch in it lands unbound, which is what a missing group line looks like");
            return;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        // 放出来的这一份用自己的频道号: 捕获时的号码属于它被复制的那台机器, 沿用会把复制品和原件绑成一组.
        // 分组留着, 号码是新的, 一个号只换一次.
        for (BlueprintComponents.SyncTorch entry : section.syncTorches()) {
            int fresh = BlueprintRestore.freshChannel(store, level.dimension(), entry.channel());
            store.putSyncTorch(BlueprintPlacement.toWorld(anchor, settings.getRotation(), settings.getMirror(),
                    entry.pos()), fresh);
        }
        // 蓝图让一个维度多出了几个频道, 客户端那一份只能整份换掉。
        HostSync.broadcastSyncGroups(level);
        trace("blueprint deploy: restored " + section.syncTorches().size() + " standing sync torch channel(s), " + ", at anchor="
                + anchor.toShortString() + " rotation=" + settings.getRotation() + " mirror=" + settings.getMirror()
                + ", and told every client the whole membership");
    }

    /**
     * The one level-free step between a file and a level: a section in world coordinates, or {@code null} whenever
     * the file is not usable - an unknown version, a size that disagrees with the file's own, a damaged entry.
     */
    @Nullable
    public static BlueprintPlacement.Budget budgetOf(@Nullable CompoundTag wholeTag, BlockPos anchor,
                                                     @Nullable Rotation rotation, @Nullable Mirror mirror) {
        BlueprintComponents.Section section = BlueprintComponents.readFile(wholeTag);
        if (section == null || anchor == null) {
            return null;
        }
        return BlueprintPlacement.budget(section, anchor, rotation, mirror);
    }

    // ----------------------------------------------------------- place side --

    /** One schematic block has just landed: whatever a blueprint expected there is written into the store. */
    public static void blockLanded(@Nullable Level level, @Nullable BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            BlueprintRestore.settle(serverLevel, pos);
        }
    }

    /**
     * An instant build has finished: one pass over everything it placed, for the cells it skipped because the world
     * already matched the blueprint.
     */
    public static void packetFinished(@Nullable Object packet, @Nullable ServerPlayer player) {
        if (packet == null || player == null) {
            return;
        }
        try {
            Object stack = invoke(packet, "stack");
            BlockPos anchor = stack instanceof ItemStack item ? anchorOf(item) : null;
            if (anchor != null) {
                BlueprintRestore.finish(player.serverLevel(), anchor);
            }
        } catch (RuntimeException failed) {
            report("packet", "an instant build could not be completed: " + failed);
        }
    }

    /**
     * A 蓝图加农炮 has finished printing. Its projectiles are still in the air, so the last pass waits for them -
     * unless there are none, which is the whole of the condition for "everything has landed".
     */
    public static void printingFinished(@Nullable Object cannon) {
        BlockPos cannonPos = positionOf(cannon);
        BlockPos anchor = printerAnchor(cannon);
        if (!(levelOf(cannon) instanceof ServerLevel serverLevel) || cannonPos == null || anchor == null) {
            return;
        }
        try {
            if (flyingBlocks(cannon)) {
                BlueprintRestore.markFinal(serverLevel.dimension(), cannonPos, anchor);
            } else {
                BlueprintRestore.finish(serverLevel, anchor);
            }
        } catch (RuntimeException failed) {
            report("printing", "a cannon's blueprint could not be settled: " + failed);
        }
    }

    /** A cannon ticked its projectiles: once the last one has landed, a print that was waiting settles. */
    public static void flyingBlocksTicked(@Nullable Object cannon) {
        if (cannon == null || flyingBlocks(cannon)) {
            return;
        }
        try {
            BlockPos cannonPos = positionOf(cannon);
            if (!(levelOf(cannon) instanceof ServerLevel serverLevel) || cannonPos == null) {
                return;
            }
            BlockPos anchor = BlueprintRestore.takeFinal(serverLevel.dimension(), cannonPos);
            if (anchor != null) {
                BlueprintRestore.finish(serverLevel, anchor);
            }
        } catch (RuntimeException failed) {
            report("flying", "a cannon's last projectiles could not be settled: " + failed);
        }
    }

    // --------------------------------------------------------------- Create --

    /** The item's own rotation and mirror - the components Create's printer places with. */
    @Nullable
    private static StructurePlaceSettings settingsOf(ItemStack stack) {
        try {
            Class<?> item = Class.forName(CREATE_SCHEMATIC_ITEM, false, BlueprintDeploy.class.getClassLoader());
            Method accessor = item.getMethod("getSettings", ItemStack.class);
            return accessor.invoke(null, stack) instanceof StructurePlaceSettings settings ? settings : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            report("settings", "a blueprint's rotation could not be read: " + unavailable);
            return null;
        }
    }

    /** The anchor the blueprint was deployed at, from the item component Create's printer reads it from. */
    @Nullable
    private static BlockPos anchorOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            Class<?> components = Class.forName(CREATE_DATA_COMPONENTS, false,
                    BlueprintDeploy.class.getClassLoader());
            Field field = components.getField("SCHEMATIC_ANCHOR");
            return readAnchor(stack, field.get(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            report("anchor", "a blueprint's anchor could not be read: " + unavailable);
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockPos readAnchor(ItemStack stack, @Nullable Object type) {
        if (!(type instanceof DataComponentType<?> component)) {
            return null;
        }
        // The component's type argument is Create's to declare and ours to trust: the value it hands back for this
        // key is the BlockPos it was registered for, and anything else is treated as "no anchor".
        Object value = stack.get((DataComponentType) component);
        return value instanceof BlockPos pos ? pos : null;
    }

    /** The anchor of the printer a cannon is using, or {@code null} once Create has cleared it. */
    @Nullable
    private static BlockPos printerAnchor(@Nullable Object cannon) {
        if (cannon == null) {
            return null;
        }
        Object printer;
        try {
            printer = cannon.getClass().getField("printer").get(cannon);
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
        return invoke(printer, "getAnchor") instanceof BlockPos pos ? pos : null;
    }

    /** Whether a cannon still has projectiles in the air. Unknown counts as "yes": waiting is the safe answer. */
    private static boolean flyingBlocks(@Nullable Object cannon) {
        if (cannon == null) {
            return true;
        }
        try {
            Field field = cannon.getClass().getField("flyingBlocks");
            return !(field.get(cannon) instanceof List<?> flying) || !flying.isEmpty();
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return true;
        }
    }

    @Nullable
    private static BlockPos positionOf(@Nullable Object cannon) {
        return cannon instanceof BlockEntity entity ? entity.getBlockPos() : null;
    }

    @Nullable
    private static Level levelOf(@Nullable Object cannon) {
        return cannon instanceof BlockEntity entity ? entity.getLevel() : null;
    }

    /** One no-argument accessor of one of Create's objects, or {@code null}. */
    @Nullable
    private static Object invoke(@Nullable Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    /** Reports a condition once per key: each of these happens once per blueprint, not once per block. */
    private static void report(String key, String message) {
        if (REPORTED.add(key)) {
            RCConfig.LOGGER.warn("[redstonecircuit] blueprint placement: {}", message);
        }
    }
}
