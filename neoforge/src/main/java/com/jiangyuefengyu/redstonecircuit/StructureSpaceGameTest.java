package com.jiangyuefengyu.redstonecircuit;

import java.io.Reader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketDimension;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The pocket dimension's two datapack files: are they shipped, and do they obey the format's rules?
 *
 * <p>Asking the server for the level cannot pass in this harness, and that is a fact about the harness rather than
 * about the files: {@code GameTestServer} builds its world from the flat world preset and bakes it against an
 * <b>empty</b> level-stem registry, while {@code WorldDimensions#bake} only ever creates levels for the keys it is
 * handed. A dedicated server bakes against the datapack's own registry, so the pocket is created there and nowhere in a
 * game test; the level itself is verified on a real server instead.
 *
 * <p>That leaves two things a test <em>can</em> hold onto, and they are the two that break in practice: the files have
 * to be at the exact paths the loader reads (a directory one letter off is silently ignored and the dimension simply
 * never exists), and the dimension type has to obey the format's arithmetic - {@code height} and {@code min_y} are
 * multiples of 16, and {@code logical_height} does not exceed {@code height}. Getting those wrong is a datapack that
 * fails to load at world startup, the worst possible place to find out.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class StructureSpaceGameTest {

    private static final String NAMESPACE = RedstoneCircuit.MODID;

    private static JsonObject readJson(GameTestHelper helper, String path) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
        Resource resource = helper.getLevel().getServer().getResourceManager().getResource(location)
                .orElse(null);
        if (resource == null) {
            helper.fail("no " + location + " on the server's data pack - the dimension cannot exist "
                    + "without it");
            return new JsonObject();
        }
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception unreadable) {
            helper.fail(location + " is not readable JSON: " + unreadable);
            return new JsonObject();
        }
    }

    @GameTest(template = "empty")
    public void thePocketDimensionIsShipped(GameTestHelper helper) {
        JsonObject dimension = readJson(helper, "dimension/structure_space.json");
        JsonObject type = readJson(helper, "dimension_type/structure_space.json");

        helper.assertTrue(PocketDimension.STRUCTURE_SPACE.location().getPath().equals("structure_space"),
                "the level key and the two file names must agree, or nothing lines up");
        helper.assertTrue(dimension.get("type").getAsString().equals(NAMESPACE + ":structure_space"),
                "the dimension must name our own dimension type, got " + dimension.get("type"));
        helper.assertTrue(dimension.getAsJsonObject("generator").get("type").getAsString()
                        .equals("minecraft:flat"),
                "the pocket is flat: nothing is generated there but what a structure brings");
        helper.assertTrue(!type.get("has_ceiling").getAsBoolean(),
                "and it is not a nether-shaped box: redstone above a structure has to work normally");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void thePocketDimensionTypeObeysTheFormat(GameTestHelper helper) {
        JsonObject type = readJson(helper, "dimension_type/structure_space.json");

        int height = type.get("height").getAsInt();
        int minY = type.get("min_y").getAsInt();
        int logicalHeight = type.get("logical_height").getAsInt();

        // DimensionType's own constructor throws on every one of these, at world load. Checking them here
        // means a bad edit fails a test rather than a server start.
        helper.assertTrue(height % 16 == 0, "height must be a multiple of 16, got " + height);
        helper.assertTrue(minY % 16 == 0, "min_y must be a multiple of 16, got " + minY);
        helper.assertTrue(logicalHeight <= height,
                "logical_height (" + logicalHeight + ") may not exceed height (" + height + ")");
        helper.assertTrue(minY <= PocketDimension.PLOT_BASE_Y
                        && PocketDimension.PLOT_BASE_Y < minY + height,
                "the plots at y=" + PocketDimension.PLOT_BASE_Y + " have to be inside the dimension");
        helper.assertTrue(type.get("monster_spawn_block_light_limit").getAsInt() == 0
                        && type.get("monster_spawn_light_level").getAsInt() == 0,
                "nothing may spawn in the pocket: it is storage, not a place");
        helper.assertTrue(!type.get("has_raids").getAsBoolean()
                        && !type.get("respawn_anchor_works").getAsBoolean()
                        && !type.get("bed_works").getAsBoolean(),
                "and it is not somewhere a player lives");
        helper.succeed();
    }
}
