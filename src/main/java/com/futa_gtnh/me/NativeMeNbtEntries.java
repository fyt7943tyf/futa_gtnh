package com.futa_gtnh.me;

import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/** Read-only traversal; retain the public NBT accessor path when the vanilla field is unavailable or subclassed. */
final class NativeMeNbtEntries {

    private static final Field CONTENTS = contentsField();

    private NativeMeNbtEntries() {}

    private static Field contentsField() {
        for (String name : new String[] { "tagMap", "field_74784_a" }) {
            try {
                Field field = NBTTagCompound.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Iterable<Map.Entry<String, NBTBase>> read(NBTTagCompound tag) {
        if (CONTENTS != null && tag.getClass() == NBTTagCompound.class) {
            try {
                return ((Map<String, NBTBase>) CONTENTS.get(tag)).entrySet();
            } catch (IllegalAccessException | RuntimeException ignored) {}
        }
        return () -> new Iterator<Map.Entry<String, NBTBase>>() {

            private final Iterator<?> keys = tag.func_150296_c()
                .iterator();

            @Override
            public boolean hasNext() {
                return keys.hasNext();
            }

            @Override
            public Map.Entry<String, NBTBase> next() {
                String key = (String) keys.next();
                return new AbstractMap.SimpleImmutableEntry<>(key, tag.getTag(key));
            }
        };
    }
}
