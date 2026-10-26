package org.lwjgl.input;

public final class Keyboard {

    public static boolean shift = true;
    public static boolean control;
    public static boolean rightControl;
    public static boolean space;
    public static boolean alt;

    public static void enableRepeatEvents(boolean enabled) {}

    public static boolean isKeyDown(int key) {
        // GuiScreen uses Command on macOS and Control elsewhere. Model the editing modifier on both.
        return (shift && key == 42) || (control && (key == 29 || key == 219)) || (rightControl && (key == 157 || key == 220))
            || (space && key == 57) || (alt && key == 56);
    }
}
