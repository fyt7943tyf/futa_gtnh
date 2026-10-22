package com.futa_gtnh.cosmetic;

import java.util.UUID;

/** Connection-scoped alias for CAR's local player cache; never changes a player's actual UUID. */
public final class CosmeticArmorIdentity {

    private Object connection;
    private UUID localId;
    private UUID serverId;

    public synchronized void connected(Object current) {
        connection = current;
        localId = null;
        serverId = null;
    }

    public synchronized void identify(Object source, UUID local, UUID server) {
        if (source == null || source != connection || local == null || server == null) return;
        localId = local;
        serverId = server;
    }

    public synchronized UUID resolve(UUID requested) {
        return localId != null && localId.equals(requested) ? serverId : requested;
    }

    public synchronized void disconnected(Object source) {
        if (source != connection) return;
        connected(null);
    }
}
