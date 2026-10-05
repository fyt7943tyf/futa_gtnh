package com.futa_gtnh.web;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 极简 JSON 输出器（只写不读）。
 *
 * <p>
 * 为什么不用 Gson：本模组的 JSON 全部是「自己拼、给浏览器读」的单向输出，
 * 引一个序列化库只是徒增一层反射和一份依赖；这里用 {@link StringBuilder}
 * 直接拼，几万条物品的搜索响应也能一次成型，没有中间对象。
 *
 * <p>
 * 用法是链式的，语法靠 {@code obj()/arr()} 与 {@code end()} 配对：
 *
 * <pre>
 * 
 * String json = WebJson.object()
 *     .k("ok")
 *     .v(true)
 *     .k("items")
 *     .arr()
 *     .v(1)
 *     .v(2)
 *     .end()
 *     .toString();
 * </pre>
 *
 * <p>
 * <b>逗号怎么处理</b>：一个 {@code needComma} 标记就够了 —— 写「值」的时候
 * 先补逗号再置位，写「键」的时候先补逗号再清位（键后面紧跟的那个值不该再有逗号）。
 * 括号类型另用一个栈记，空容器也能收对。
 *
 * <p>
 * 中文不转义：响应头声明了 {@code charset=utf-8}，直出 UTF-8 比写成 Unicode 转义序列
 * 体积小三倍。
 */
public final class WebJson {

    private final StringBuilder out;
    private final Deque<Character> stack = new ArrayDeque<>();
    private boolean needComma;

    private WebJson(int capacity) {
        this.out = new StringBuilder(capacity);
    }

    /** 新建一个对象，返回的实例已经处在对象内部。 */
    public static WebJson object() {
        return new WebJson(512).obj();
    }

    /** 新建一个数组，返回的实例已经处在数组内部。 */
    public static WebJson array() {
        return new WebJson(512).arr();
    }

    public WebJson obj() {
        pre();
        out.append('{');
        stack.push('{');
        needComma = false;
        return this;
    }

    public WebJson arr() {
        pre();
        out.append('[');
        stack.push('[');
        needComma = false;
        return this;
    }

    public WebJson end() {
        if (stack.isEmpty()) return this;
        out.append(stack.pop() == '{' ? '}' : ']');
        needComma = true;
        return this;
    }

    public WebJson k(String name) {
        pre();
        string(name);
        out.append(':');
        needComma = false;
        return this;
    }

    public WebJson v(String value) {
        pre();
        if (value == null) {
            out.append("null");
        } else {
            string(value);
        }
        needComma = true;
        return this;
    }

    public WebJson v(long value) {
        pre();
        out.append(value);
        needComma = true;
        return this;
    }

    public WebJson v(boolean value) {
        pre();
        out.append(value ? "true" : "false");
        needComma = true;
        return this;
    }

    public WebJson v(double value) {
        pre();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            out.append('0');
        } else {
            out.append(Math.round(value * 1000.0D) / 1000.0D);
        }
        needComma = true;
        return this;
    }

    public WebJson nul() {
        pre();
        out.append("null");
        needComma = true;
        return this;
    }

    private void pre() {
        if (needComma) out.append(',');
        needComma = false;
    }

    private void string(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x7F) {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) out.append('0');
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    /**
     * 输出结果，并<b>自动补齐没闭合的容器</b>。
     *
     * <p>
     * 这一步不是可有可无的保险：{@code end()} 是对称写法，漏一个就是一段坏 JSON，
     * 而浏览器的 {@code JSON.parse} 会把整个响应一起丢掉 —— 表现是整页空白，
     * 而不是「少了一点数据」。所以这里把「收尾」从调用方的责任改成这个类自己的责任：
     * 各个响应方法只管往下写，最后少掉的括号由这里补上。
     *
     * <p>
     * 重复调用是安全的：第一次就把栈清空了，之后再调只会返回同样的字符串。
     */
    @Override
    public String toString() {
        while (!stack.isEmpty()) {
            out.append(stack.pop() == '{' ? '}' : ']');
        }
        return out.toString();
    }
}
