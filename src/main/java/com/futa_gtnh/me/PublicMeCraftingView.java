package com.futa_gtnh.me;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.supply.SupplyTracker;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;

/** Bounded GUI adapter; all AE symbols stay out of common packet and client classes. */
public final class PublicMeCraftingView {

    private static final int PAGE_SIZE = 8, MAX_CATALOG = 8192;

    private PublicMeCraftingView() {}

    public static NBTTagCompound handle(EntityPlayerMP player, int operation, NBTTagCompound key, long amount,
        String expectedPlan, String search, int catalogPage, int planPage) {
        String notice = "";
        boolean canCraft = allowed(player);
        if (operation == 1) {
            IAEStack<?> output = stack(SupplyTracker.readKey(key));
            if (!canCraft) notice = "公共 ME 网络未就绪或没有合成权限";
            else if (output == null || amount < 1 || amount > 1_000_000_000L) notice = "请输入 1 至 1,000,000,000 的有效数量";
            else {
                ICraftingGrid grid = PublicMeNetwork.grid()
                    .getCache(ICraftingGrid.class);
                if (grid == null || !grid.getCraftingMultiPatterns()
                    .containsKey(output)) notice = "该产物当前没有已编码且在线的 AE 样板";
                else {
                    output.setStackSize(amount);
                    notice = PublicMeCrafting.plan(player, output);
                }
            }
        } else if (operation == 2) {
            notice = PublicMeCrafting.confirm(player, expectedPlan);
        } else if (operation == 3) {
            notice = PublicMeCrafting.cancel(player, expectedPlan);
        }
        NBTTagCompound response = new NBTTagCompound();
        response.setBoolean("online", PublicMeNetwork.isOnline());
        response.setBoolean("canCraft", canCraft);
        response.setString("notice", notice);
        NBTTagCompound job = PublicMeCrafting.snapshot(player);
        if (job.hasKey("output", 10) && !bounded(job.getCompoundTag("output"))) {
            job.removeTag("output");
            job.setBoolean("hiddenOutput", true);
        }
        NBTTagList allRows = job.getTagList("rows", 10);
        int page = page(job, "row", planPage, allRows.tagCount());
        NBTTagList rows = new NBTTagList();
        int skipped = 0;
        for (int i = page * PAGE_SIZE; i < Math.min(allRows.tagCount(), (page + 1) * PAGE_SIZE); i++) {
            NBTTagCompound row = allRows.getCompoundTagAt(i);
            if (bounded(row)) rows.appendTag(row.copy());
            else skipped++;
        }
        job.setInteger("hiddenRows", skipped);
        job.setTag("rows", rows);
        response.setTag("job", job);
        List<NBTTagCompound> catalog = new ArrayList<>();
        boolean capped = false;
        if (canCraft) {
            ICraftingGrid crafting = PublicMeNetwork.grid()
                .getCache(ICraftingGrid.class);
            String query = search == null ? ""
                : search.toLowerCase(Locale.ROOT)
                    .trim();
            if (crafting != null) {
                int scanned = 0;
                for (IAEStack<?> output : crafting.getCraftingMultiPatterns()
                    .keySet()) {
                    if (scanned++ >= MAX_CATALOG) {
                        capped = true;
                        break;
                    }
                    Object identity = key(output);
                    if (identity == null) continue;
                    NBTTagCompound entry = SupplyTracker.writeKey(identity);
                    String name = output.getDisplayName();
                    if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT)
                        .contains(query)
                        && !output.getModId()
                            .toLowerCase(Locale.ROOT)
                            .contains(query))
                        continue;
                    entry.setString("name", name);
                    if (!bounded(entry)) {
                        capped = true;
                        continue;
                    }
                    catalog.add(entry);
                }
            }
        }
        catalog.sort(Comparator.comparing(entry -> entry.getString("name")));
        page = page(response, "catalog", catalogPage, catalog.size());
        NBTTagList outputs = new NBTTagList();
        for (int i = page * PAGE_SIZE; i < Math.min(catalog.size(), (page + 1) * PAGE_SIZE); i++)
            outputs.appendTag(catalog.get(i));
        response.setTag("catalog", outputs);
        response.setBoolean("catalogCapped", capped);
        return response;
    }

    private static boolean allowed(EntityPlayerMP player) {
        if (player == null || !PublicMeNetwork.isOnline()) return false;
        ISecurityGrid security = PublicMeNetwork.grid()
            .getCache(ISecurityGrid.class);
        return security == null || security.hasPermission(player, SecurityPermissions.CRAFT);
    }

    private static int page(NBTTagCompound tag, String prefix, int requested, int count) {
        int pages = Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requested, pages - 1));
        tag.setInteger(prefix + "Page", page);
        tag.setInteger(prefix + "Pages", pages);
        tag.setInteger(prefix + "Count", count);
        return page;
    }

    private static Object key(IAEStack<?> stack) {
        return stack instanceof IAEItemStack ? ItemKey.of(((IAEItemStack) stack).getItemStack())
            : stack instanceof IAEFluidStack ? FluidKey.of(((IAEFluidStack) stack).getFluidStack()) : null;
    }

    private static IAEStack<?> stack(Object key) {
        return key instanceof ItemKey ? AEItemStack.create(((ItemKey) key).prototype())
            : key instanceof FluidKey ? AEFluidStack.create(((FluidKey) key).prototype()) : null;
    }

    private static boolean bounded(NBTTagCompound tag) {
        try {
            return tag.toString()
                .length() <= 2048 && CompressedStreamTools.compress(tag).length <= 768;
        } catch (java.io.IOException invalid) {
            return false;
        }
    }
}
