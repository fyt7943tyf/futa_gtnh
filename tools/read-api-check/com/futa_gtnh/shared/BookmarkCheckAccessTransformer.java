package com.futa_gtnh.shared;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/** Mirrors NEI's NBT access transformers in the registry-only launcher, which does not start Forge. */
public final class BookmarkCheckAccessTransformer implements IClassTransformer {

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        String field;
        if ("net.minecraft.nbt.NBTTagCompound".equals(transformedName)) field = "tagMap";
        else if ("net.minecraft.nbt.NBTTagList".equals(transformedName)) field = "tagList";
        else return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (FieldNode member : node.fields) {
            if (field.equals(member.name)) {
                member.access = (member.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
            }
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
