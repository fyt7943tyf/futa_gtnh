package org.lwjgl.input;

public final class Keyboard {

    public static boolean shift = true;

    public static boolean isKeyDown(int key) {
        return shift && key == 42;
    }
}
