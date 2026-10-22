package com.jiangyuefengyu.redstonecircuit.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 探针 mixin 的注入约定，钉在源码上：回调参数的类型必须与它注入的方法的返回类型一致。
 *
 * <p>Why this is a source-text guard and not a loaded-class check: the bug it exists for is visible
 * <em>before</em> anything runs - Mixin compares the <em>erased</em> callback descriptor against the
 * target method, so a non-void target plus a {@code CallbackInfo} parameter is a compile-time fact, and
 * the failure it produces ("Invalid descriptor ... CallbackInfoReturnable is required!") is thrown
 * while the mixin applies, at startup, on a machine that has Sodium installed. Loading Sodium's
 * classes inside a unit test to learn the same fact would need a game, and the descriptor is already
 * spelled out in the annotation - so the annotation is what is read.
 */
class SodiumMesherProbeInjectionTest {

    /** {@code method = "getBlockRenderer()Lnet/.../BlockRenderer;"} -> 捕获返回描述符。 */
    private static final Pattern METHOD_SPEC = Pattern.compile("method\\s*=\\s*\"[^\"]*\\)([^\"]*)\"");

    /** 注入处理方法 {@code private void redstonecircuit$xxx(...)} -> 捕获参数表（混入的 body 不在 {} 里）。 */
    private static final Pattern HANDLER = Pattern.compile("void\\s+redstonecircuit\\$\\w+\\s*\\(([^)]*)\\)");

    private static final Pattern TARGETS = Pattern.compile("@Mixin\\(targets\\s*=\\s*\"([^\"]+)\"");

    /** 裸 {@code CallbackInfo}：{@code CallbackInfoReturnable} 不算 - 它带 Returnable 后缀。 */
    private static final Pattern BARE_CALLBACK_INFO = Pattern.compile("CallbackInfo(?!Returnable)");

    @Test
    @DisplayName("目标返回 BlockRenderer，回调参数就必须是 CallbackInfoReturnable<...>，不能是裸 CallbackInfo")
    void theCallbackParameterMatchesTheTargetReturnType() throws IOException {
        String probe = probe();

        String returnDescriptor = group(METHOD_SPEC, probe, 1, "the @Inject method spec");
        assertFalse(returnDescriptor.equals("V"),
                "前提：javap 从 sodium-mc1.21.1-0.8.13-fabric 打印的是 "
                        + "'public BlockRenderer getBlockRenderer()' - 目标不是 void。这条红了说明说明文字与"
                        + "被注入的方法已经对不上，先回去读 jar，再读这个测试");
        String returned = simpleName(returnDescriptor);

        String declared = declaredType(
                group(HANDLER, probe, 1, "the @Inject handler's parameter list"));
        assertTrue(declared.startsWith("CallbackInfoReturnable<") && declared.endsWith(">"),
                "目标方法返回 " + returned + "，所以回调参数只能是 CallbackInfoReturnable<...>："
                        + "CallbackInjector 拿擦除后的描述符去比两个候选（CallbackInfo / CallbackInfoReturnable），"
                        + "非 void 的目标配 CallbackInfo 会抛 InvalidInjectionException - 真机日志里那句 "
                        + "'Invalid descriptor ... CallbackInfoReturnable is required!' - 整个 mixin 不应用，"
                        + "探针那一行日志永远不会出现。现在是 " + declared);

        String argument = declared.substring("CallbackInfoReturnable<".length(), declared.length() - 1).trim();
        assertTrue(argument.equals("Object") || argument.equals(returned),
                "泛型只用来读：擦除后 Mixin 只看 CallbackInfoReturnable 这个类名，所以这里既可以写目标的"
                        + "返回类型 " + returned + "，也可以写 Object（本文件的写法：它让这个文件不出现任何 Sodium"
                        + "类型，与这个包里另外三个 Mixin 的约定一致）。现在写的是 " + argument);

        assertFalse(BARE_CALLBACK_INFO.matcher(stripComments(probe)).find(),
                "并且代码里（注释不算：说明文字本来就要提它）不能再出现裸 CallbackInfo：探针注入的目标不返回 "
                        + "void，任何一处裸 CallbackInfo 都只会是同一处笔误的复制");
    }

    @Test
    @DisplayName("探针只看不写：HEAD 注入、从不 cancel、从不改返回值")
    void itOnlyWatches() throws IOException {
        String probe = stripComments(probe());

        assertTrue(probe.contains("at = @At(\"HEAD\")"),
                "注入点是 HEAD：在 getBlockRenderer() 的第一条指令处报告'建块路径经过这里'，"
                        + "Sodium 随后的方法体照原样跑完 - 这一点是本文件'不影响建块'说法的根据");
        assertFalse(probe.contains(".cancel()"),
                "它不取消：BlockRenderCache#getBlockRenderer 一旦不返回，Sodium 的建块任务会 NPE，"
                        + "而一个探针永远不该能改动被观察的东西");
        assertFalse(probe.contains("setReturnValue("),
                "它也不设返回值，理由同上");
        assertFalse(probe.contains("cancellable"),
                "不声明 cancellable：没有 cancel、没有 setReturnValue，加上它只会让读者以为这个探针能干预");
    }

    @Test
    @DisplayName("@Mixin 的目标类与 method 描述符指向同一个包：抄错一行会让注入静默落空")
    void theTargetAndTheDescriptorAgree() throws IOException {
        String probe = probe();

        String target = group(TARGETS, probe, 1, "the @Mixin targets string");
        String returnDescriptor = group(METHOD_SPEC, probe, 1, "the @Inject method spec");
        String returned = simpleName(returnDescriptor);

        assertEquals(packageOf(target), packageOf(internalName(returnDescriptor)),
                "method 描述符里的返回类型 " + returned + " 与 @Mixin 的目标 " + target
                        + " 必须同包：这两个字符串都不经过混淆（remap = false），抄错一个字母的后果是"
                        + "require = 0 把注入失败吞掉，只剩一条日志");
    }

    private static String probe() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                "SodiumMesherProbeMixin.java");
    }

    /** 去掉块注释与行注释，供"代码里是否还有裸 CallbackInfo"这条断言使用。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)(?<!:)//.*$", " ");
    }

    /** {@code Lnet/a/B;} -> {@code net/a/B}，即描述符里的内部名。 */
    private static String internalName(String returnDescriptor) {
        String descriptor = returnDescriptor.trim();
        assertTrue(descriptor.startsWith("L") && descriptor.endsWith(";"),
                "目标返回类型应当是一个对象描述符，读到的却是 '" + descriptor + "'");
        return descriptor.substring(1, descriptor.length() - 1);
    }

    /** {@code Lnet/a/B; } -> {@code B}；这个测试只处理对象描述符。 */
    private static String simpleName(String returnDescriptor) {
        String descriptor = returnDescriptor.trim();
        assertTrue(descriptor.startsWith("L") && descriptor.endsWith(";"),
                "目标返回类型应当是一个对象描述符，读到的却是 '" + descriptor + "'");
        return descriptor.substring(descriptor.lastIndexOf('/') + 1, descriptor.length() - 1);
    }

    private static String packageOf(String binaryOrInternalName) {
        String name = binaryOrInternalName.replace('/', '.');
        int dot = name.lastIndexOf('.');
        assertTrue(dot > 0, "读到的不是全限定名：'" + binaryOrInternalName + "'");
        return name.substring(0, dot);
    }

    /** 参数表取第一个参数的类型部分：{@code CallbackInfoReturnable<Object> callback} -> 前者。 */
    private static String declaredType(String parameterList) {
        String first = parameterList.split(",")[0].trim();
        int space = first.lastIndexOf(' ');
        assertTrue(space > 0, "参数表读不出'类型 名字'的形状：'" + parameterList + "'");
        return first.substring(0, space).trim();
    }

    private static String group(Pattern pattern, String text, int group, String what) {
        Matcher matcher = pattern.matcher(text);
        assertTrue(matcher.find(), what + " 没有出现：这个 guard 读的是源码文本，找不到就是文件结构变了");
        return matcher.group(group);
    }

    private static String read(String... parts) throws IOException {
        Path direct = Path.of("", parts);
        if (Files.isRegularFile(direct)) {
            return Files.readString(direct, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the file on purpose");
    }
}
