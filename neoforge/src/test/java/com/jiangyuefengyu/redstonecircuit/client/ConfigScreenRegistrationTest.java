package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Config button, as a property of the source: four lines that a build cannot see and a dedicated server cannot
 * survive being wrong.
 *
 * <h2>Why the source and not the classes</h2>
 * <ul>
 *   <li><b>A config alone is not a button.</b> The mod list enables Config only when it finds an
 *       {@code IConfigScreenFactory} extension point, so a registered {@code ModConfigSpec} on its own leaves the
 *       button grey - the check below is the extension point being registered at all.</li>
 *   <li><b>It is one call, in one place.</b> The screen is NeoForge's generic {@code ConfigurationScreen}, so the
 *       registration must name it; a hand-written screen would be a second source of truth about the config keys.</li>
 *   <li><b>The client class must stay behind a dist guard.</b> {@code ConfigurationScreen} does not exist on a
 *       dedicated server, and a plain call from the common mod class would resolve it while the server starts. The
 *       guard is the whole difference between "compiles" and "starts".</li>
 *   <li><b>Nothing common may name it.</b> One direct reference in a common class reintroduces the load path the
 *       guard exists to prevent, and it is the kind of line that gets copied.</li>
 * </ul>
 */
class ConfigScreenRegistrationTest {

    private static final String HOLDER = "src/main/java/com/jiangyuefengyu/redstonecircuit/client/"
            + "ConfigScreenRegistration.java";
    private static final String MAIN_CLASS = "src/main/java/com/jiangyuefengyu/redstonecircuit/RedstoneCircuit.java";

    @Test
    @DisplayName("配置界面注册在唯一一个持有类里：显式注册 IConfigScreenFactory，界面用 NeoForge 的通用 ConfigurationScreen")
    void theExtensionPointIsRegisteredAndNamesTheGenericScreen() throws IOException {
        String holder = read(HOLDER);
        assertTrue(holder.contains("modContainer.registerExtensionPoint(IConfigScreenFactory.class,"),
                "the mod list asks for an extension point, not for a config: without this line the button stays grey");
        assertTrue(holder.contains("new ConfigurationScreen(container, parent)"),
                "NeoForge's own screen walks every section of our ModConfigSpec, so no key of ours is duplicated here");
        assertTrue(holder.contains("IConfigScreenFactory.class"),
                "registered by class, which is how the mod list looks it up again");
    }

    @Test
    @DisplayName("持有类是 @OnlyIn(Dist.CLIENT)：ConfigurationScreen 在专用服务器上不存在")
    void theHolderIsClientOnly() throws IOException {
        String holder = read(HOLDER);
        assertTrue(holder.contains("@OnlyIn(Dist.CLIENT)"),
                "the annotation is the statement that nothing on the server path may load this class");
        assertTrue(holder.contains("ConfigurationScreen"),
                "and it is the only place the client class is named, so the annotation covers all of it");
    }

    @Test
    @DisplayName("公共侧只在 FMLEnvironment.dist.isClient() 守卫里调用它")
    void theCommonSideCallsItBehindTheDistGuard() throws IOException {
        String main = read(MAIN_CLASS);
        int guard = main.indexOf("FMLEnvironment.dist.isClient()");
        int call = main.indexOf("ConfigScreenRegistration.register(");
        assertTrue(guard >= 0, "the common mod class has to ask the dist before it touches a client class");
        assertTrue(call > guard, "and the call has to come after the question, not before it");
        String guarded = main.substring(guard, call);
        assertFalse(guarded.contains("}"),
                "nothing may close the guard between the condition and the call, or the call runs on a server");
        assertFalse(main.contains("new ConfigurationScreen("),
                "the common class names no client class itself: the reference lives in the guarded holder only");
    }

    @Test
    @DisplayName("client 包以外不许出现 ConfigurationScreen：一条直接引用就把服务端启动路径修回原样")
    void noCommonSourceNamesTheClientScreen() throws IOException {
        Path mainRoot = resolveDirectory("src/main/java");
        List<String> offenders = new ArrayList<>();
        int walked = 0;
        try (Stream<Path> files = Files.walk(mainRoot)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = mainRoot.relativize(file).toString().replace('\\', '/');
                if (relative.contains("/client/")) {
                    continue;
                }
                walked++;
                if (Files.readString(file, StandardCharsets.UTF_8).contains("ConfigurationScreen")) {
                    offenders.add(relative);
                }
            }
        }
        assertTrue(walked > 100,
                "the scan has to have walked the common tree, or an empty result proves nothing: " + walked);
        assertTrue(offenders.isEmpty(),
                "a common class naming the client screen is a dedicated server loading it: " + offenders);
    }

    private static String read(String relative) throws IOException {
        return Files.readString(resolve(relative), StandardCharsets.UTF_8);
    }

    private static Path resolve(String relative) throws IOException {
        Path underNeoforge = Path.of("neoforge").resolve(relative);
        Path direct = Path.of(relative);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("could not find " + relative + " from " + Path.of("").toAbsolutePath());
    }

    /** The same two shapes, for a directory: the walk starts at its root, so it is not "a file that exists". */
    private static Path resolveDirectory(String relative) throws IOException {
        Path underNeoforge = Path.of("neoforge").resolve(relative);
        Path direct = Path.of(relative);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IOException("could not find directory " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
