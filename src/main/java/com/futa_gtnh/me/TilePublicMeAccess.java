package com.futa_gtnh.me;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.AEApi;
import appeng.api.exceptions.FailedConnection;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.tile.TileEvent;
import appeng.tile.events.TileEventType;
import appeng.tile.grid.AENetworkTile;

/** A real remote cable connection; it never exposes a second copy of the public storage provider. */
public final class TilePublicMeAccess extends AENetworkTile {

    private IGridConnection connection;
    private int port = -1;
    private String problem = "等待公共 ME 核心";

    public TilePublicMeAccess() {
        getProxy().setFlags(GridFlags.DENSE_CAPACITY);
        getProxy().setIdlePowerUsage(1);
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection direction) {
        return AECableType.DENSE;
    }

    public int getPort() {
        return port;
    }

    public void nextPort() {
        disconnect();
        port = (port + 1) % PublicMeNetwork.PORT_COUNT;
        markDirty();
        refreshConnection();
    }

    public String status() {
        if (problem != null) return problem;
        int used = connection == null ? 0 : connection.getUsedChannels();
        return "公共 ME 接入器：端口 " + (port + 1)
            + "，本链路 "
            + used
            + "/32 频道，端口合计 "
            + PublicMeNetwork.portUsedChannels(port)
            + "/32；潜行右键切换端口。"
            + (PublicMeNetwork.isOnline() ? "" : " 网络正在寻路或离线。");
    }

    @Override
    public void onReady() {
        super.onReady();
        if (!worldObj.isRemote) PublicMeNetwork.register(this);
    }

    @Override
    public void invalidate() {
        disconnect();
        PublicMeNetwork.unregister(this);
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        disconnect();
        PublicMeNetwork.unregister(this);
        super.onChunkUnload();
    }

    void disconnect() {
        if (connection != null) {
            connection.destroy();
            connection = null;
        }
    }

    void refreshConnection() {
        if (worldObj == null || worldObj.isRemote || isInvalid()) return;
        IGridNode local = getActionableNode();
        if (local == null || !PublicMeNetwork.isAttachable()) {
            disconnect();
            problem = PublicMeNetwork.status();
            return;
        }
        if (hasForeignController(local)) {
            disconnect();
            problem = "本地线缆连接着独立 ME 控制器；已隔离，避免与公共控制器冲突";
            return;
        }
        if (port < 0 || port >= PublicMeNetwork.PORT_COUNT) {
            port = PublicMeNetwork.choosePort();
            markDirty();
        }
        IGridNode remote = PublicMeNetwork.portNode(port);
        if (remote == null) {
            disconnect();
            problem = "所选公共 ME 端口尚未就绪";
            return;
        }
        if (connection != null) {
            if ((connection.a() == local && connection.b() == remote)
                || (connection.b() == local && connection.a() == remote)) {
                problem = null;
                return;
            }
            disconnect();
        }
        try {
            connection = AEApi.instance()
                .createGridConnection(local, remote);
            problem = null;
        } catch (FailedConnection failure) {
            problem = "公共 ME 连接未建立：网络权限或连接状态不允许";
        }
    }

    static boolean hasForeignController(IGridNode local) {
        IGrid grid = local.getGrid();
        boolean foreign = false;
        for (Class<? extends IGridHost> type : grid.getMachinesClasses()) {
            if (!PublicMeNetwork.isControllerClass(type)) continue;
            for (IGridNode node : grid.getMachines(type)) {
                if (!PublicMeNetwork.isManagedController(node.getMachine())) foreign = true;
            }
        }
        if (!foreign) return false;
        // Only isolate the offending local branch. Ignore every public remote edge when tracing it.
        Set<IGridNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<IGridNode> queue = new ArrayDeque<>();
        queue.add(local);
        while (!queue.isEmpty()) {
            IGridNode node = queue.removeFirst();
            if (!seen.add(node)) continue;
            if (seen.size() > 8192) return true;
            if (PublicMeNetwork.isControllerClass(
                node.getMachine()
                    .getClass())
                && !PublicMeNetwork.isManagedController(node.getMachine())) return true;
            for (IGridConnection link : node.getConnections()) {
                IGridNode other = link.getOtherSide(node);
                if (!(other.getMachine() instanceof TilePublicMeCore) && !seen.contains(other)) queue.addLast(other);
            }
        }
        return false;
    }

    @TileEvent(TileEventType.WORLD_NBT_READ)
    public void readPublicMeAccess(NBTTagCompound tag) {
        port = tag.hasKey("futaPublicMePort") ? tag.getInteger("futaPublicMePort") : -1;
    }

    @TileEvent(TileEventType.WORLD_NBT_WRITE)
    public void writePublicMeAccess(NBTTagCompound tag) {
        tag.setInteger("futaPublicMePort", port);
    }
}
