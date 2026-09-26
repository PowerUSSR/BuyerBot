package org.mineserver.buyerbot;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;

/**
 * Fake player NPC via ProtocolLib packets.
 * Spawns a player-model entity client-side only.
 */
public class FakeNPC {

    // Fixed high entity ID — must not collide with real server entities.
    // Real entities use IDs assigned by atomic counter (usually < 1 million for normal servers).
    private static final int ENTITY_ID = 9_000_001;

    private final Plugin plugin;
    private final UUID uuid = UUID.randomUUID();
    private final Location location;
    private String skinTexture;
    private String skinSignature;
    private boolean created = false;

    // Players this NPC has been spawned for (client-side)
    private final Set<UUID> spawnedFor = new HashSet<>();

    public FakeNPC(Plugin plugin, Location location) {
        this.plugin = plugin;
        this.location = location.clone();
    }

    // ── Skin ───────────────────────────────────────────────────────────────

    public void setSkin(String texture, String signature) {
        this.skinTexture = texture;
        this.skinSignature = signature;
        // Respawn for all online players to apply new skin
        List<Player> toRespawn = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (spawnedFor.contains(p.getUniqueId())) {
                despawnFor(p);
                toRespawn.add(p);
            }
        }
        for (Player p : toRespawn) spawnFor(p);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /** Mark this NPC as ready (call before spawnForAll). */
    public void create() {
        created = true;
    }

    public void spawnForAll() {
        if (!created) return;
        for (Player p : Bukkit.getOnlinePlayers()) spawnFor(p);
    }

    public void despawnForAll() {
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) despawnFor(p);
    }

    /**
     * Remove a player from the "spawned" tracking without sending destroy packet.
     * Use before PlayerRespawnEvent / PlayerChangedWorldEvent to allow re-spawn.
     */
    public void forgetPlayer(UUID playerId) {
        spawnedFor.remove(playerId);
    }

    // ── Per-player ─────────────────────────────────────────────────────────

    public void spawnFor(Player player) {
        if (!created || spawnedFor.contains(player.getUniqueId())) return;
        ProtocolManager pm = ProtocolLibrary.getProtocolManager();

        WrappedGameProfile profile = new WrappedGameProfile(uuid, "Скупщик");
        if (skinTexture != null) {
            String sig = (skinSignature != null) ? skinSignature : "";
            profile.getProperties().put("textures",
                WrappedSignedProperty.fromValues("textures", skinTexture, sig));
        }

        // 1. Add to tab list — required for the client to load the skin
        // ProtocolLib can't write to private final EnumSet/List fields in 1.20.1.
        // Fix: use sun.misc.Unsafe to bypass the restriction + ProtocolLib converters
        // to get correct NMS enum constants regardless of SRG vs Mojmap naming.
        try {
            PacketContainer info = pm.createPacket(PacketType.Play.Server.PLAYER_INFO);
            Object nmsPacket = info.getHandle();

            // Get Unsafe via reflection (no compile-time import needed)
            Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafeF = unsafeCls.getDeclaredField("theUnsafe");
            theUnsafeF.setAccessible(true);
            Object unsafe = theUnsafeF.get(null);
            java.lang.reflect.Method offsetM =
                unsafeCls.getMethod("objectFieldOffset", java.lang.reflect.Field.class);
            java.lang.reflect.Method putObjM =
                unsafeCls.getMethod("putObject", Object.class, long.class, Object.class);

            // Get NMS Action enum constants via ProtocolLib converter (handles SRG names correctly)
            com.comphenix.protocol.reflect.EquivalentConverter<EnumWrappers.PlayerInfoAction> aConv =
                EnumWrappers.getPlayerInfoActionConverter();
            Object nmsAdd = aConv.getGeneric(EnumWrappers.PlayerInfoAction.ADD_PLAYER);
            @SuppressWarnings({"unchecked", "rawtypes"})
            EnumSet actionSet = EnumSet.of((Enum) nmsAdd);
            try {
                Object nmsListed = aConv.getGeneric(EnumWrappers.PlayerInfoAction.UPDATE_LISTED);
                if (nmsListed != null) ((EnumSet) actionSet).add((Enum) nmsListed);
            } catch (Exception ignored) {}

            // Build NMS Entry via ProtocolLib's PlayerInfoData converter
            PlayerInfoData pd = new PlayerInfoData(uuid, 0, true,
                EnumWrappers.NativeGameMode.CREATIVE, profile, null);
            Object nmsEntry = PlayerInfoData.getConverter().getGeneric(pd);

            // Set both private-final fields (actions + entries) via Unsafe
            for (java.lang.reflect.Field f : nmsPacket.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                long off = (long) offsetM.invoke(unsafe, f);
                if (f.getType().equals(EnumSet.class)) {
                    putObjM.invoke(unsafe, nmsPacket, off, actionSet);
                } else if (f.getType().equals(List.class)) {
                    putObjM.invoke(unsafe, nmsPacket, off, Collections.singletonList(nmsEntry));
                }
            }

            pm.sendServerPacket(player, info);
        } catch (Exception e) {
            plugin.getLogger().warning("[BuyerBot] NPC info packet: " + e);
        }

        // 2. Spawn player entity
        try {
            PacketContainer spawn = pm.createPacket(PacketType.Play.Server.NAMED_ENTITY_SPAWN);
            spawn.getIntegers().write(0, ENTITY_ID);
            spawn.getUUIDs().write(0, uuid);
            spawn.getDoubles().write(0, location.getX());
            spawn.getDoubles().write(1, location.getY());
            spawn.getDoubles().write(2, location.getZ());
            spawn.getBytes().write(0, toAngle(location.getYaw()));   // yaw
            spawn.getBytes().write(1, toAngle(location.getPitch())); // pitch
            pm.sendServerPacket(player, spawn);
        } catch (Exception e) {
            plugin.getLogger().warning("[BuyerBot] NPC spawn packet: " + e);
        }

        // 3. Set head rotation (so the NPC faces the right direction)
        try {
            PacketContainer head = pm.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
            head.getIntegers().write(0, ENTITY_ID);
            head.getBytes().write(0, toAngle(location.getYaw()));
            pm.sendServerPacket(player, head);
        } catch (Exception e) {
            plugin.getLogger().warning("[BuyerBot] NPC head rotation: " + e);
        }

        spawnedFor.add(player.getUniqueId());

        // Remove from tab list after 3 seconds — keeps the visual entity but removes from /tab list
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !spawnedFor.contains(player.getUniqueId())) return;
            try {
                PacketContainer removeInfo = pm.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
                removeInfo.getUUIDLists().write(0, Collections.singletonList(uuid));
                pm.sendServerPacket(player, removeInfo);
            } catch (Exception e) {
                plugin.getLogger().warning("[BuyerBot] NPC info remove: " + e.getMessage());
            }
        }, 60L);
    }

    public void despawnFor(Player player) {
        if (!spawnedFor.remove(player.getUniqueId())) return;
        if (!player.isOnline()) return;
        ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        try {
            PacketContainer destroy = pm.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            destroy.getIntLists().write(0, Collections.singletonList(ENTITY_ID));
            pm.sendServerPacket(player, destroy);
        } catch (Exception e) {
            plugin.getLogger().warning("[BuyerBot] NPC destroy packet: " + e.getMessage());
        }
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public int getEntityId() { return ENTITY_ID; }
    public boolean isCreated() { return created; }
    public String getLocationWorld() {
        return location.getWorld() != null ? location.getWorld().getName() : "";
    }
    public Location getLocation() { return location.clone(); }

    // ── Util ───────────────────────────────────────────────────────────────

    /** Converts degrees to a Minecraft angle byte (0–255). */
    private static byte toAngle(float degrees) {
        return (byte) Math.floor(degrees * 256.0f / 360.0f);
    }
}
