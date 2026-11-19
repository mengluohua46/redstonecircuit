package com.jiangyuefengyu.redstonecircuit.client;

import java.util.HashMap;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.block.SurfaceWireBlock;
import com.jiangyuefengyu.redstonecircuit.block.SurfaceWireEntity;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.client.model.loading.v1.FabricBakedModelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the strips of a sheet: the half of the line that runs towards an in-plane neighbour, one per joined
 * neighbour and per 元件 the sheet drives.
 *
 * <p><b>Why a renderer instead of a state value.</b> 2026-11-11 tried the obvious route first: a {@code Link} value
 * meaning "joined towards this neighbour". At a junction the two cells then judged each other differently, the state
 * kept flipping, and the flip propagated as shape updates until the server gave up - the GameTest failure read
 * <i>Too many chained neighbor updates</i>. A connection is something that is <b>seen</b>, not something that is
 * <b>stored</b>: this asks the world once per frame and writes nothing, so there is no state to cascade.
 *
 * <p>The dots stay in the block model ({@code surface_dust_<face>_dot}, one per face of the state); this adds the
 * strips on top, lifted by {@link #LIFT} so the two do not fight in the same plane.
 */
public final class SurfaceWireRenderer implements BlockEntityRenderer<SurfaceWireEntity> {

    /** How far a strip is lifted off the surface, in blocks. Three thousandths is a third of a texture pixel. */
    private static final float LIFT = 0.003F;

    /**
     * The strip model of one face and slot, per dust: the prefix comes from the block, because two dusts are the same
     * geometry in two materials (红石粉（贴面） and 超导红石粉（贴面）).
     */
    private static final Map<String, String[][]> NAMES = new HashMap<>();

    private static String nameOf(String prefix, Direction face, int slot) {
        String[][] perPrefix = NAMES.computeIfAbsent(prefix, key -> {
            String[][] names = new String[6][];
            for (Direction each : Direction.values()) {
                String[] perFace = new String[4];
                for (int index = 0; index < 4; index++) {
                    perFace[index] = key + each.getName() + "_flat_" + index;
                }
                names[each.ordinal()] = perFace;
            }
            return names;
        });
        return perPrefix[face.ordinal()][slot];
    }

    public SurfaceWireRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SurfaceWireEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        Level level = entity.getLevel();
        if (level == null) {
            return;
        }
        BlockState state = entity.getBlockState();
        if (!(state.getBlock() instanceof SurfaceWireBlock)) {
            return;
        }
        BlockPos pos = entity.getBlockPos();
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        RandomSource random = RandomSource.create();
        String prefix = SurfaceWireBlock.stripPrefixOf(state);
        for (Direction face : SurfaceWireBlock.faces(state)) {
            Direction out = face.getOpposite();
            for (int slot = 0; slot < 4; slot++) {
                Direction towards = SurfaceWireBlock.ring(face).get(slot);
                // 同一格里另一个面就是「另一个面交替的地方」：墙上的粉朝下、地上的粉朝墙，各自画半截，
                // 在共用的那条棱上对齐 —— 这就是拐角连起来的样子，不需要跨格的模型。
                if (!SurfaceWireBlock.joined(level, pos, face, towards)
                        && !SurfaceWireBlock.drawsTowards(level, pos, towards)
                        && !SurfaceWireBlock.hasFace(state, towards)) {
                    continue;
                }
                BakedModel model = modelFor(nameOf(prefix, face, slot));
                pose.pushPose();
                pose.translate(out.getStepX() * LIFT, out.getStepY() * LIFT, out.getStepZ() * LIFT);
                dispatcher.getModelRenderer().tesselateBlock(level, model, state, pos, pose,
                        buffer.getBuffer(RenderType.cutout()), false, random, 42L, packedOverlay);
                pose.popPose();
            }
        }
    }

    private static BakedModel modelFor(String path) {
        // A model that no blockstate names is baked under a variant of its own, and the variant is the loader's
        // business: NeoForge's is the "standalone" one, Fabric's keeps the registered models by id and asks
        // FabricBakedModelManager for them. Asking the NeoForge way here returns the missing model, which is
        // what the author saw as 无效贴图 (2026-11-12).
        return ((FabricBakedModelManager) Minecraft.getInstance().getModelManager())
                .getModel(ResourceLocation.fromNamespaceAndPath("redstonecircuit", path));
    }
}
