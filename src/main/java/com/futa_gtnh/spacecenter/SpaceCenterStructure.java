package com.futa_gtnh.spacecenter;

import static com.gtnewhorizon.structurelib.structure.StructureUtility.transpose;

import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;

/**
 * 深空采矿指挥中心的结构定义：形状数据，以及把形状装配成 {@link IStructureDefinition} 的过程。
 *
 * <p>
 * 结构 = 7×7 底座（1 层）+ 若干 7×7 中间层 + 7×7 顶盖（1 层），整体随控制器朝向旋转：
 * 控制器在底座前缘正中（{@code '~'} 在深度第 0 行），结构朝控制器背面延伸。
 *
 * <p>
 * <b>形状的写法是 StructureLib {@code transpose} 的输入</b>：{@code String[层(上→下)][深度行(前→后)][水平列]}，
 * 偏移量直接按这个输入数组的下标给（{@link #HORIZONTAL_OFFSET} 就是 {@code '~'} 的列号），不是转置之后的。
 *
 * <p>
 * <b>为什么单独抽一个类：</b>StructureLib 要求形状里的每一个字符都有对应的元素绑定，少一个，
 * {@code build()} 就抛 {@code Missing Structure Element bindings}——而这个构建是惰性的，编译、注册、
 * 服务端冒烟全都碰不到它，直到第一次成型检查、或者玩家在 NEI 里按 R 看多方块预览才崩
 * （2.1.0 的中间层用 {@code '.'} 表示空气却没绑定元素，就是这么崩的）。所以这里把装配写成
 * 「先收集形状里的全部字符，再逐个登记」，并且不依赖 GT 运行时，
 * 回归检查（{@code verifySpaceCenterMachines}）拿真实的 StructureLib 把它完整装配一遍。
 */
final class SpaceCenterStructure {

    /** 三个 piece 的名字。 */
    static final String PIECE_BASE = "base";
    static final String PIECE_LAYER = "layer";
    static final String PIECE_TOP = "top";

    /** 控制器。StructureLib 自己认这个字符，不需要绑定元素。 */
    static final char CONTROLLER = '~';
    /** 采掘桁架。 */
    static final char TRUSS = 'C';
    /** 全息投影核心。 */
    static final char HOLO = 'K';
    /** 信号天线阵列。 */
    static final char ANTENNA = 'A';
    /** 底座边缘：输入总线/输入仓/输出总线/输出仓/维护仓/能源仓，或者桁架。 */
    static final char HATCH = 'H';
    /** 中间层的内部：必须是空气。 */
    static final char AIR = '.';

    /** 元素种类：形状字符与具体元素之间的一层，{@code MTESpaceMiningCenter} 按它给元素。 */
    enum Kind {
        /** 采掘桁架方块。 */
        TRUSS,
        /** 全息投影核心方块。 */
        HOLO_CORE,
        /** 信号天线阵列方块。 */
        ANTENNA,
        /** 空气。 */
        AIR,
        /** 仓/口，或者退而求其次的桁架。 */
        HATCH_OR_TRUSS
    }

    /** 控制器在输入数组里的列号 = 水平偏移。 */
    static final int HORIZONTAL_OFFSET = 3;
    /** 控制器在输入数组里的深度行号 = 深度偏移。 */
    static final int DEPTH_OFFSET = 0;
    /** 每一层的边长（行数 = 每行字符数）。 */
    static final int WIDTH = 7;

    // spotless:off
    /** 底座：桁架地板，前缘正中是控制器，四边可以贴仓/口。 */
    static final String[][] BASE = { { "HHH~HHH",
        "HCCCCCH",
        "HCCCCCH",
        "HCCCCCH",
        "HCCCCCH",
        "HCCCCCH",
        "HHHHHHH" } };

    /** 中间层：桁架边框，正中一根全息投影核心，其余必须是空气。 */
    static final String[][] LAYER = { { "CCCCCCC",
        "C.....C",
        "C.....C",
        "C..K..C",
        "C.....C",
        "C.....C",
        "CCCCCCC" } };

    /** 顶盖：桁架边框 + 内圈 8 个全息核心 + 正中与外围一圈共 17 根信号天线。 */
    static final String[][] TOP = { { "CCCCCCC",
        "CAAAAAC",
        "CAKKKAC",
        "CAKAKAC",
        "CAKKKAC",
        "CAAAAAC",
        "CCCCCCC" } };
    // spotless:on

    private static final String[][][] PIECES = { BASE, LAYER, TOP };
    private static final String[] PIECE_NAMES = { PIECE_BASE, PIECE_LAYER, PIECE_TOP };

    /** 最矮结构（底座 + 顶盖）的桁架数。 */
    static final int TRUSS_MIN = count(BASE, TRUSS) + count(TOP, TRUSS);
    /** 每多一层中间层增加的桁架数。 */
    static final int TRUSS_PER_LAYER = count(LAYER, TRUSS);

    private SpaceCenterStructure() {}

    /** 形状字符对应的元素种类；不是本结构用到的字符返回 null。 */
    static Kind kindOf(char c) {
        switch (c) {
            case TRUSS:
                return Kind.TRUSS;
            case HOLO:
                return Kind.HOLO_CORE;
            case ANTENNA:
                return Kind.ANTENNA;
            case AIR:
                return Kind.AIR;
            case HATCH:
                return Kind.HATCH_OR_TRUSS;
            default:
                return null;
        }
    }

    /**
     * 装配结构定义：三个 piece 的形状，加上形状里出现的每一个字符的元素。
     *
     * @param elementFor 按元素种类给出具体的结构元素
     * @throws IllegalStateException 形状不合规、出现了 {@link #kindOf} 不认识的字符，或者元素没有实现
     */
    static <T> IStructureDefinition<T> build(Function<Kind, IStructureElement<T>> elementFor) {
        StructureDefinition.Builder<T> builder = StructureDefinition.builder();
        Set<Character> chars = new TreeSet<>();
        for (int p = 0; p < PIECES.length; p++) {
            String[][] shape = PIECES[p];
            for (String[] level : shape) {
                for (String row : level) {
                    if (row.length() != WIDTH) {
                        throw new IllegalStateException("深空采矿指挥中心的结构形状 " + PIECE_NAMES[p] + " 有一行不是 " + WIDTH + " 格宽");
                    }
                    for (char c : row.toCharArray()) {
                        if (c == CONTROLLER) continue;
                        // 「Missing Structure Element bindings」的前哨：谁往形状里写了新字符却没在 kindOf 里
                        // 登记，装配当场就报出是哪个字符，而不是等玩家在 NEI 里按 R 的时候崩
                        if (kindOf(c) == null) {
                            throw new IllegalStateException(
                                "深空采矿指挥中心的结构形状用到了没有登记的字符 '" + c + "'（" + PIECE_NAMES[p] + "）");
                        }
                        chars.add(c);
                    }
                }
            }
            builder.addShape(PIECE_NAMES[p], transpose(shape));
        }
        for (char c : chars) {
            IStructureElement<T> element = elementFor.apply(kindOf(c));
            if (element == null) {
                throw new IllegalStateException("深空采矿指挥中心的结构元素 '" + c + "' 没有实现");
            }
            builder.addElement(c, element);
        }
        return builder.build();
    }

    private static int count(String[][] shape, char c) {
        int n = 0;
        for (String[] level : shape) {
            for (String row : level) {
                for (int i = 0; i < row.length(); i++) {
                    if (row.charAt(i) == c) n++;
                }
            }
        }
        return n;
    }
}
