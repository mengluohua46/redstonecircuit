package com.jiangyuefengyu.redstonecircuit.logic;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * <b>调频器 - "这一次右键该做什么" 的判定表, 一个不碰世界的纯函数.</b>
 *
 * <p>两个手势 (右键 / Shift+右键) 的答案只取决于三件事实: 手里握着一个频道没有、目标是不是已经在某个频道上、以及手里
 * 那个频道和眼前这个维度是不是同一个。把这三件事实写成一张表, 而不是写进右键处理器里, 是因为这张表就是这个物品的<b>全部
 * 语义</b>, 而它有三十二种输入组合: 每一条都值得在没有世界的情况下钉住 ({@code TunerRuleTest}), 而右键处理器只剩下"把
 * 选中的那个动作执行出去"这一件事。
 *
 * <p>表本身没有顺序上的讲究, 但优先级有: <b>维度不合</b>压过一切 - 频道号是每个维度各自自增的, 所以"3 号"在主世界和
 * 下界是两个不相干的组, 拿一个别的维度的号码去绑一支火把, 结果是两边都变得不可预期。所以这一条一旦成立, 动作就定下来,
 * 什么也不改, 只把原因说出来。
 */
public final class TunerRule {

    private TunerRule() {
    }

    /**
     * 一个频道: 号码, 以及它属于哪个维度.
     *
     * <p>维度是频道身份的一半而不是装饰。号码由每个维度各自的计数器发下去
     * ({@link com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore#allocateSyncChannel()}), 所以 "5 号" 在
     * 主世界和下界是两个不同的组; 把两者一起存进物品, 才回答得出"手里这个频道在这个维度里还算不算数"。
     *
     * <p>存的是维度的 <b>id</b> ({@code minecraft:overworld}) 而不是一个 {@code ResourceKey}: 一个物品要能被抄进
     * 蓝图、被截图、被别的存档认出来, 而 id 是它唯一稳定的写法。
     */
    public record Channel(int number, String dimension) {

        public static final Codec<Channel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.INT.fieldOf("channel").forGetter(Channel::number),
                        Codec.STRING.fieldOf("dimension").forGetter(Channel::dimension))
                .apply(instance, Channel::new));

        /** 与 {@code StructureProductData#STREAM_CODEC} 同一套写法: 物品的 tooltip 在客户端读, 所以它得上线。 */
        public static final StreamCodec<ByteBuf, Channel> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Channel::number,
                ByteBufCodecs.STRING_UTF8, Channel::dimension,
                Channel::new);

        /** 是不是一个真频道号 - {@link SyncTorchRule#UNBOUND} 与 0 都不是。 */
        public boolean isBound() {
            return SyncTorchRule.isChannel(number);
        }

        /** 这个频道是不是这个维度里的; 维度的写法只在调用方那一处 ({@code FrequencyTunerItem#dimensionId})。 */
        public boolean inDimension(String dimensionId) {
            return dimension.equals(dimensionId);
        }
    }

    /** 一次右键该做的事, 一次一件。 */
    public enum Action {
        /** 目标加入手里这个频道 (手里没有就新开一个) - 它本来就在别的频道上则见 {@link #MOVE}。 */
        BIND,
        /** 目标离开它原来的频道, 加入手里这个。 */
        MOVE,
        /** 把目标的频道取进手里。 */
        COPY,
        /** 把<b>目标火把</b>的频道清掉: 它退回"没有频道"的状态, 手里的频道不动。 */
        UNBIND,
        /** 没有什么可做: 这一下是冲着<b>火把</b>去的, 而它本来就没有频道。手里的频道也不动。 */
        NONE,
        /** 手里那个频道属于别的维度: 什么也不改。 */
        OTHER_DIMENSION;

        /** 做完之后对玩家说的那一句; {@link #NONE} 没有话可说 (它什么也没做)。 */
        @Nullable
        public Message message() {
            return switch (this) {
                case BIND -> Message.BOUND;
                case MOVE -> Message.MOVED;
                case COPY -> Message.COPIED;
                case UNBIND -> Message.UNBOUND;
                case NONE -> null;
                case OTHER_DIMENSION -> Message.OTHER_DIMENSION;
            };
        }
    }

    /** 一句反馈, 每一条都对应语言文件里的一个键 (与判定的动作一一对应, 于是两者不可能漂开)。 */
    public enum Message {
        BOUND("message.redstonecircuit.tuner.bound", true),
        MOVED("message.redstonecircuit.tuner.moved", true),
        COPIED("message.redstonecircuit.tuner.copied", true),
        UNBOUND("message.redstonecircuit.tuner.unbound", false),
        CLEARED("message.redstonecircuit.tuner.cleared", false),
        OTHER_DIMENSION("message.redstonecircuit.tuner.other_dimension", false);

        private final String key;
        private final boolean namesChannel;

        Message(String key, boolean namesChannel) {
            this.key = key;
            this.namesChannel = namesChannel;
        }

        /** 翻译键。 */
        public String key() {
            return key;
        }

        /** 这句话要不要一个频道号做参数 (清空与维度不合两句不需要)。 */
        public boolean namesChannel() {
            return namesChannel;
        }
    }

    /**
     * 判定表本身.
     *
     * @param handHasChannel 手里握着一个真频道
     * @param handOtherDimension 手里那个频道属于别的维度; 只有 {@code handHasChannel} 为真时才有意义
     * @param targetHasChannel 目标已经在一个频道上
     * @param targetOnSameChannel 目标就在手里这个频道上, 于是"加入"是一次空操作而不是搬动; 只有两边都有频道时才有意义
     * @param sneaking 潜行, 也就是手势的另一半
     * @return 这一次右键该做的事
     */
    public static Action decide(boolean handHasChannel, boolean handOtherDimension,
                                boolean targetHasChannel, boolean targetOnSameChannel,
                                boolean sneaking) {
        if (handHasChannel && handOtherDimension) {
            return Action.OTHER_DIMENSION;
        }
        if (sneaking) {
            // Shift 半边是"对着这一支"的那种手势: 它自己有频道就把那一个清掉(退回无频道)。它本来就没有 -> 什么也不做:
            // 手里的频道不该被一次"冲着火把去"的点击清掉, 而清空手里有它自己的手势(Shift+右键空地/别的方块)。
            return targetHasChannel ? Action.UNBIND : Action.NONE;
        }
        if (targetHasChannel) {
            // 目标已经在一个频道上: 手里<b>没有</b>频道时, 这一下是"选中它那一个"——不是另开一个新频道把它拽走。
            // 手里有别的频道才是搬动, 已经在同一个上就是重复一次绑定(空操作)。
            return !handHasChannel ? Action.COPY : (targetOnSameChannel ? Action.BIND : Action.MOVE);
        }
        // 目标还没有频道: 手里有就绑上去, 没有就由调用方新开一个。
        return Action.BIND;
    }
}
