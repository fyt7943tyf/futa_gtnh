package com.futa_gtnh.api;

import com.futa_gtnh.shared.SharedStorageManager;

/**
 * Versioned, read-only access to the server-wide shared warehouse.
 *
 * <p>
 * Call status and snapshot methods on the server thread after scheduling through the consumer's own
 * server task queue. No player, terminal block, AE network or permission lookup is required. This API
 * does not save, reload, broadcast or modify storage. It has no dependency on AE2-Web.
 */
public final class SharedStorageReadApi {

    public static final int API_VERSION = 1;

    private SharedStorageReadApi() {}

    /** Safe to call from any thread, including before the server starts. */
    public static int getApiVersion() {
        return API_VERSION;
    }

    /** Returns lifecycle/revision information without copying the inventory. */
    public static SharedStorageReadStatus getStatus() {
        return SharedStorageManager.getReadApiStatus();
    }

    /** Returns detached item and fluid rows captured under one storage lock. */
    public static SharedStorageSnapshot snapshot() {
        return SharedStorageManager.getReadApiSnapshot();
    }
}
