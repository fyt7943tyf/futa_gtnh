package com.futa_gtnh.api;

/** One completed sample window of a node. {@code age} 0 is the newest completed sample. */
public final class IoNodeFlowSample {

    private final int age;
    private final long itemIn;
    private final long itemOut;
    private final long fluidIn;
    private final long fluidOut;

    public IoNodeFlowSample(int age, long itemIn, long itemOut, long fluidIn, long fluidOut) {
        this.age = age;
        this.itemIn = itemIn;
        this.itemOut = itemOut;
        this.fluidIn = fluidIn;
        this.fluidOut = fluidOut;
    }

    /** 0 = 最近一个完成的采样窗，每 +1 往前推一个采样期。 */
    public int getAge() {
        return age;
    }

    public long getItemIn() {
        return itemIn;
    }

    public long getItemOut() {
        return itemOut;
    }

    public long getFluidIn() {
        return fluidIn;
    }

    public long getFluidOut() {
        return fluidOut;
    }
}
