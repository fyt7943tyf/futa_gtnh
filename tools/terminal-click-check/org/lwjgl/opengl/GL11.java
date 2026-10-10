package org.lwjgl.opengl;

/** Only the NEI foreground's two GL state calls are bypassed; real texture binding is validated by the fixture. */
public final class GL11 {

    public static void glColor4f(float r, float g, float b, float a) {}

    public static void glDisable(int capability) {}
}
