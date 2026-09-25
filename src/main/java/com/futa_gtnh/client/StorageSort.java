package com.futa_gtnh.client;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 共享存储列表的排序方式。
 *
 * <p>
 * 三种排序各有各的用途：找熟悉的东西按名称，看「我到底攒了多少」按数量，
 * 排查「哪个模组塞了一堆垃圾」按模组。
 */
public final class StorageSort {

    private StorageSort() {}

    public static final int BY_NAME = 0;
    public static final int BY_AMOUNT = 1;
    public static final int BY_MOD = 2;
    public static final int MODE_COUNT = 3;

    private static final Comparator<StorageViewEntry> NAME = new Comparator<StorageViewEntry>() {

        @Override
        public int compare(StorageViewEntry a, StorageViewEntry b) {
            int result = a.getSearchName()
                .compareTo(b.getSearchName());
            if (result != 0) return result;
            return a.getRegistryName()
                .compareTo(b.getRegistryName());
        }
    };

    private static final Comparator<StorageViewEntry> AMOUNT = new Comparator<StorageViewEntry>() {

        @Override
        public int compare(StorageViewEntry a, StorageViewEntry b) {
            // 数量多的排前面
            int result = Long.compare(b.getAmount(), a.getAmount());
            if (result != 0) return result;
            return NAME.compare(a, b);
        }
    };

    private static final Comparator<StorageViewEntry> MOD = new Comparator<StorageViewEntry>() {

        @Override
        public int compare(StorageViewEntry a, StorageViewEntry b) {
            int result = a.getModName()
                .compareToIgnoreCase(b.getModName());
            if (result != 0) return result;
            return NAME.compare(a, b);
        }
    };

    public static Comparator<StorageViewEntry> comparator(int mode) {
        switch (mode) {
            case BY_AMOUNT:
                return AMOUNT;
            case BY_MOD:
                return MOD;
            default:
                return NAME;
        }
    }

    public static void sort(List<StorageViewEntry> list, int mode) {
        Collections.sort(list, comparator(mode));
    }

    public static int next(int mode) {
        return (mode + 1) % MODE_COUNT;
    }

    /**
     * 把配置文件里存的整数钳位成合法的排序方式，越界回退到「按数量」
     * （1.3.2 起的默认值）。
     */
    public static int fromIndex(int index) {
        return index >= 0 && index < MODE_COUNT ? index : BY_AMOUNT;
    }

    /** 语言文件里的键。 */
    public static String translationKey(int mode) {
        switch (mode) {
            case BY_AMOUNT:
                return "futa_gtnh.gui.sort.amount";
            case BY_MOD:
                return "futa_gtnh.gui.sort.mod";
            default:
                return "futa_gtnh.gui.sort.name";
        }
    }
}
