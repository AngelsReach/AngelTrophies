package com.github.kristianvld.angeltrophies;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.spigotmc.event.entity.EntityDismountEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TrophyManager implements Listener {

    private final List<Trophy> trophies;

    private final Map<UUID, Long> sneaking = new HashMap<>();
    private static final long SITTING_TIMEOUT = 10;

    private final Map<UUID, Long> dismount = new HashMap<>();
    private static final long DISMOUNT_TIMEOUT = 20;

    private final Map<UUID, Integer> justPlacedTrophy = new HashMap<>();

    public TrophyManager(List<Trophy> trophies) {
        this.trophies = new ArrayList<>(trophies);
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
    }

    public Trophy getTrophy(ItemStack item) {
        for (Trophy trophy : trophies) {
            if (trophy.matches(item)) {
                return trophy;
            }
        }
        return null;
    }

    public Trophy getTrophy(Entity entity) {
        for (Trophy trophy : trophies) {
            if (trophy.matches(entity)) {
                return trophy;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || !player.isSneaking()) {
            return;
        }

        if (justPlacedTrophy.getOrDefault(player.getUniqueId(), 0) == player.getTicksLived()) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            return;
        }

        EquipmentSlot otherHand = event.getHand() == EquipmentSlot.HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        if (getTrophy(player.getEquipment().getItem(otherHand)) != null) {
            return;
        }

        ItemStack item = event.getItem();
        Trophy trophy = getTrophy(item);
        if (trophy == null) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        Block block = event.getClickedBlock().getRelative(event.getBlockFace());
        BlockFace face = event.getBlockFace().getOppositeFace();
        if (trophy.place(player, block, face, event.getHand()) != null) {
            justPlacedTrophy.put(player.getUniqueId(), player.getTicksLived());
        }

    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractAtEntityEvent event) {
        Trophy trophy = getTrophy(event.getRightClicked());
        if (trophy == null) {
            return;
        }
        event.setCancelled(true);
        if (!event.getPlayer().isSneaking()) {
            if (event.getPlayer().isInsideVehicle()) {
                return;
            }
            Entity seat = trophy.getSeat(event.getRightClicked());
            if (seat != null) {
                if (seat.getPassengers().isEmpty()) {
                    seat.addPassenger(event.getPlayer());
                }
            }
        } else {
            trophy.pickup(event.getPlayer(), event.getRightClicked());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Trophy.SLAB_TYPE) {
            for (Entity e : event.getBlock().getWorld().getNearbyEntities(event.getBlock().getLocation().add(0.5, 0.5, 0.5), 0.5, 0.5, 0.5)) {
                if (getTrophy(e) != null && e.getPersistentDataContainer().has(Trophy.SEAT_KEY, UUIDTagType.UUID)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        long time = event.getPlayer().getTicksLived();
        if (!event.getPlayer().isInsideVehicle() && dismount.getOrDefault(uuid, 0L) < time) {
            if (event.getPlayer().getLocation().getBlock().getType() == Trophy.SLAB_TYPE) {
                for (Entity e : event.getPlayer().getLocation().getBlock().getWorld().getNearbyEntities(event.getPlayer().getLocation().getBlock().getLocation().add(0.5, 0.5, 0.5), 0.5, 2.5, 0.5)) {
                    if (e.getPersistentDataContainer().has(Trophy.TROPHY_PARENT_KEY, UUIDTagType.UUID)) {
                        if (event.isSneaking()) {
                            sneaking.put(uuid, time + SITTING_TIMEOUT);
                        } else if (sneaking.getOrDefault(uuid, 0L) > time) {
                            if (e.getPassengers().isEmpty()) {
                                e.addPassenger(event.getPlayer());
                            }
                        }
                        return;
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            dismount.put(player.getUniqueId(), event.getEntity().getTicksLived() + DISMOUNT_TIMEOUT);
            if (player.getLocation().add(0, 1, 0).getBlock().getType() == Trophy.SLAB_TYPE) {
                for (Entity e : player.getLocation().getBlock().getWorld().getNearbyEntities(player.getLocation().getBlock().getLocation().add(0.5, 0.5, 0.5), 0.5, 2.5, 0.5)) {
                    if (e.getPersistentDataContainer().has(Trophy.TROPHY_PARENT_KEY, UUIDTagType.UUID)) {
                        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                            Location loc = event.getDismounted().getLocation().getBlock().getLocation().add(0.5, 0.5, 0.5);
                            loc.setDirection(player.getLocation().getDirection());
                            player.teleport(loc);
                        });
                        break;
                    }
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sneaking.remove(event.getPlayer().getUniqueId());
        dismount.remove(event.getPlayer().getUniqueId());
        justPlacedTrophy.remove(event.getPlayer().getUniqueId());
    }


}
