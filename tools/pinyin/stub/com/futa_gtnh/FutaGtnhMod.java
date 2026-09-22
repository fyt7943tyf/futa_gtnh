package com.futa_gtnh;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 只用于在游戏外单独跑 {@code Pinyin} / {@code PinyinCheck} 的桩件。
 *
 * <p>
 * {@code client.Pinyin} 唯一的非 JDK 依赖就是 {@code FutaGtnhMod.LOG}。
 * 真实的 {@code FutaGtnhMod} 上挂着 {@code @Mod} 注解、还引用着一大堆
 * Forge 类，为了在命令行里验证拼音表而把整套 Forge 拉进 classpath 不值得。
 * 所以这里放一个只有 {@code LOG} 的替身，编译时把它排在真实类之前即可。
 *
 * <p>
 * <b>它不是产品代码，也不会被打进 mod 的 jar。</b>
 */
public class FutaGtnhMod {

    public static final String MODID = "futa_gtnh";
    public static final Logger LOG = LogManager.getLogger("pinyin-check");
}
