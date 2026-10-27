package com.futa_gtnh.me.logistics;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;

/** Versioned sealed checkpoints; unknown/corrupt state stays byte-for-byte equivalent NBT and cannot activate. */
final class NativeLogisticsNbt {

    private NativeLogisticsNbt() {}

    static NBTTagCompound seal(NBTTagCompound tag) {
        tag.setInteger("version", 1);
        tag.removeTag("sha256");
        tag.setString("sha256", hash(tag));
        return tag;
    }

    static void validate(NBTTagCompound tag) {
        if (tag.hasNoTags()) return;
        if (!tag.hasKey("version", 3) || tag.getInteger("version") != 1
            || !tag.hasKey("parts", 10)
            || !tag.hasKey("sha256", 8))
            throw new IllegalStateException("Unsupported or incomplete native logistics checkpoint");
        NBTTagCompound payload = (NBTTagCompound) tag.copy();
        payload.removeTag("sha256");
        if (!hash(payload).equals(tag.getString("sha256")))
            throw new IllegalStateException("Native logistics checkpoint checksum mismatch");
    }

    private static String hash(NBTTagCompound tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                canonical(tag, output);
            }
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes.toByteArray());
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void text(String text, DataOutputStream output) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void canonical(NBTBase tag, DataOutputStream output) throws IOException {
        output.writeByte(tag.getId());
        if (tag instanceof NBTTagCompound compound) {
            List<String> keys = new ArrayList<>();
            for (Object key : compound.func_150296_c()) keys.add((String) key);
            Collections.sort(keys);
            output.writeInt(keys.size());
            for (String key : keys) {
                text(key, output);
                canonical(compound.getTag(key), output);
            }
        } else if (tag instanceof NBTTagList list) {
            NBTTagList copy = (NBTTagList) list.copy();
            output.writeInt(copy.tagCount());
            // Reverse removal is linear and uses the public vanilla API for every list element type.
            while (copy.tagCount() > 0) canonical(copy.removeTag(copy.tagCount() - 1), output);
        } else if (tag instanceof NBTTagByteArray array) {
            byte[] bytes = array.func_150292_c();
            output.writeInt(bytes.length);
            output.write(bytes);
        } else if (tag instanceof NBTTagIntArray array) {
            int[] values = array.func_150302_c();
            output.writeInt(values.length);
            for (int value : values) output.writeInt(value);
        } else if (tag instanceof NBTBase.NBTPrimitive primitive) {
            if (tag.getId() == 5) output.writeInt(Float.floatToRawIntBits(primitive.func_150288_h()));
            else if (tag.getId() == 6) output.writeLong(Double.doubleToRawLongBits(primitive.func_150286_g()));
            else output.writeLong(primitive.func_150291_c());
        } else text(tag.toString(), output);
    }
}
