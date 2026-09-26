package org.mineserver.buyerbot;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class BuyerBotPlugin extends JavaPlugin implements Listener {

    private FakeNPC fakeNpc;

    private Economy economy;
    private int currentPage = 0;
    private long nextRotationTime;
    private static final long CYCLE_MS = 4L * 60 * 60 * 1000;

    private File dataFile;
    private FileConfiguration dataConfig;

    // 3 страницы по 9 предметов
    private final List<List<ShopItem>> pages = new ArrayList<>();
    // soldCounts[страница][слот]
    private int[][] soldCounts = new int[3][9];

    // ==================== ENABLE / DISABLE ====================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        buildPages();
        loadData();

        if (!setupEconomy()) {
            getLogger().severe("Vault/Economy не найден! Плагин отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        setupNpcInteractListener();
        getServer().getScheduler().runTaskLater(this, this::spawnOrFindBot, 10L);
        startCycleTimer();
        startRespawnTicker();
        getLogger().info("BuyerBot включён. Текущая страница: " + (currentPage + 1));
    }

    @Override
    public void onDisable() {
        if (fakeNpc != null) fakeNpc.despawnForAll();
        saveData();
        getLogger().info("BuyerBot отключён, данные сохранены.");
    }

    private void setupNpcInteractListener() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
            new PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    if (fakeNpc == null || !fakeNpc.isCreated()) return;
                    int entityId = event.getPacket().getIntegers().read(0);
                    if (entityId != fakeNpc.getEntityId()) return;
                    event.setCancelled(true);
                    Player player = event.getPlayer();
                    getServer().getScheduler().runTask(BuyerBotPlugin.this, () -> {
                        if (player.isOnline()) player.openInventory(buildGui());
                    });
                }
            });
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    // ==================== СТРАНИЦЫ ====================

    private void buildPages() {
        // Страница 1
        List<ShopItem> p1 = new ArrayList<>();
        p1.add(new ShopItem(Material.DIRT,         "Земля",                    64, 0.5, 20));
        p1.add(new ShopItem(Material.SNOW_BLOCK,   "Блок снега",               64, 0.7, 15));
        p1.add(new ShopItem(Material.SANDSTONE,    "Песчаник",                 64, 0.6, 18));
        p1.add(new ShopItem(Material.OAK_LOG,      "Дуб бревно",               64, 1.0, 12));
        p1.add(new ShopItem(Material.SPRUCE_LOG,   "Еловое бревно",            64, 1.0, 12));
        p1.add(new ShopItem(Material.BRICKS,       "Кирпичи",                  64, 2.0, 10));
        p1.add(new ShopItem(Material.BARREL,       "Бочка",                    32, 1.5, 14));
        p1.add(new ShopItem(Material.SMOOTH_STONE, "Гладкий камень",           32, 3.0,  8));
        p1.add(new ShopItem(Material.WHITE_WOOL,   "Белая шерсть",             16, 1.5, 16));
        pages.add(p1);

        // Страница 2
        List<ShopItem> p2 = new ArrayList<>();
        p2.add(new ShopItem(Material.GRASS_BLOCK,          "Дёрн",                          64, 0.7, 18));
        p2.add(new ShopItem(Material.STONE,                "Камень",                        64, 1.0, 16));
        p2.add(new ShopItem(Material.COBBLED_DEEPSLATE,    "Колотый глубинный сланец",      64, 1.5, 16));
        p2.add(new ShopItem(Material.RED_SANDSTONE,        "Красный песчаник",              64, 0.8, 18));
        p2.add(new ShopItem(Material.MANGROVE_LOG,         "Мангровое дерево",              64, 1.5, 12));
        p2.add(new ShopItem(Material.CHERRY_LOG,           "Вишнёвое бревно",               64, 1.5, 12));
        p2.add(new ShopItem(Material.MOSSY_STONE_BRICKS,  "Замшелые каменные кирпичи",     64, 1.7, 14));
        p2.add(new ShopItem(Material.DIORITE,              "Диорит",                        64, 0.6, 18));
        p2.add(new ShopItem(Material.GRANITE,              "Гранит",                        64, 0.6, 18));
        pages.add(p2);

        // Страница 3
        List<ShopItem> p3 = new ArrayList<>();
        p3.add(new ShopItem(Material.WARPED_NYLIUM,               "Искажённый нилий",               64, 2.0, 16));
        p3.add(new ShopItem(Material.NETHERRACK,                  "Незерак",                        64, 0.8, 20));
        p3.add(new ShopItem(Material.CRIMSON_STEM,                "Багровый стебель",               32, 2.5, 15));
        p3.add(new ShopItem(Material.WARPED_STEM,                 "Искажённый стебель",             32, 2.5, 15));
        p3.add(new ShopItem(Material.WHITE_CONCRETE,              "Белый бетон",                    64, 3.0, 20));
        p3.add(new ShopItem(Material.BOOKSHELF,                   "Книжная полка",                  32, 5.0, 16));
        p3.add(new ShopItem(Material.POLISHED_BLACKSTONE_BRICKS,  "Полированно-чернитные кирпичи",  16, 6.0,  8));
        p3.add(new ShopItem(Material.FURNACE,                     "Плавильная печь",                10, 5.0,  6));
        p3.add(new ShopItem(Material.ICE,                         "Лёд",                            64, 1.0, 20));
        pages.add(p3);
    }

    // ==================== ДАННЫЕ ====================

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            currentPage = 0;
            nextRotationTime = System.currentTimeMillis() + CYCLE_MS;
            soldCounts = new int[3][9];
            return;
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        currentPage = dataConfig.getInt("current-page", 0);
        nextRotationTime = dataConfig.getLong("next-rotation", System.currentTimeMillis() + CYCLE_MS);
        // Если время ротации уже прошло (сервер был остановлен) — сбрасываем на следующий цикл от текущего момента
        if (nextRotationTime <= System.currentTimeMillis()) {
            nextRotationTime = System.currentTimeMillis() + CYCLE_MS;
        }
        soldCounts = new int[3][9];
        for (int pg = 0; pg < 3; pg++) {
            for (int sl = 0; sl < 9; sl++) {
                soldCounts[pg][sl] = dataConfig.getInt("sold." + pg + "." + sl, 0);
            }
        }
    }

    private void saveData() {
        if (dataConfig == null) dataConfig = new YamlConfiguration();
        dataConfig.set("current-page", currentPage);
        dataConfig.set("next-rotation", nextRotationTime);
        for (int pg = 0; pg < 3; pg++) {
            for (int sl = 0; sl < 9; sl++) {
                dataConfig.set("sold." + pg + "." + sl, soldCounts[pg][sl]);
            }
        }
        try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }

    // ==================== НПС (FancyNpcs) ====================

    private void spawnOrFindBot() {
        String worldName = getConfig().getString("bot-location.world", "");
        if (worldName.isEmpty()) {
            getLogger().info("BuyerBot: позиция не задана. Используйте /buyerbot spawn.");
            return;
        }
        World world = getServer().getWorld(worldName);
        if (world == null) {
            getLogger().warning("Мир '" + worldName + "' не найден! Используйте /buyerbot spawn.");
            return;
        }
        double x = getConfig().getDouble("bot-location.x", 0.5);
        double y = getConfig().getDouble("bot-location.y", 64);
        double z = getConfig().getDouble("bot-location.z", 0.5);
        float yaw = (float) getConfig().getDouble("bot-location.yaw", 0.0);
        String skinTex = getConfig().getString("skin.texture", null);
        String skinSig = getConfig().getString("skin.signature", null);
        createNPCAt(new Location(world, x, y, z, yaw, 0), skinTex, skinSig);
    }

    private void createNPCAt(Location loc, String skinTex, String skinSig) {
        if (fakeNpc != null) fakeNpc.despawnForAll();
        fakeNpc = new FakeNPC(this, loc);
        if (skinTex != null && skinSig != null) fakeNpc.setSkin(skinTex, skinSig);
        fakeNpc.create();
        fakeNpc.spawnForAll();
        getLogger().info("BuyerBot: NPC создан на " + loc.getWorld().getName() +
            " x=" + (int)loc.getX() + " y=" + (int)loc.getY() + " z=" + (int)loc.getZ());
    }

    // ==================== ЦИКЛ / РОТАЦИЯ ====================

    private void startCycleTimer() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() >= nextRotationTime) {
                    rotatePage();
                }
            }
        }.runTaskTimer(this, 20L * 60, 20L * 60); // проверка каждую минуту
    }

    private void startRespawnTicker() {
        new BukkitRunnable() {
            @Override public void run() {
                if (fakeNpc == null || !fakeNpc.isCreated()) return;
                Location npcLoc = fakeNpc.getLocation();
                if (npcLoc.getWorld() == null) return;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.getWorld().getName().equals(fakeNpc.getLocationWorld())) {
                        fakeNpc.forgetPlayer(p.getUniqueId());
                        continue;
                    }
                    double distSq = p.getLocation().distanceSquared(npcLoc);
                    if (distSq <= 64 * 64) {
                        fakeNpc.spawnFor(p); // нет эффекта если уже заспавнен
                    } else if (distSq > 80 * 80) {
                        fakeNpc.forgetPlayer(p.getUniqueId()); // вышел за радиус — забыть
                    }
                }
            }
        }.runTaskTimer(BuyerBotPlugin.this, 20L * 5, 20L * 5); // каждые 5 секунд
    }

    private void rotatePage() {
        currentPage = (currentPage + 1) % 3;
        soldCounts[currentPage] = new int[9];
        nextRotationTime = System.currentTimeMillis() + CYCLE_MS;
        saveData();

        String[] pageNames = {"Страница 1 (Лес и камень)", "Страница 2 (Земля и дерево)", "Страница 3 (Незер и редкости)"};
        getServer().broadcastMessage("");
        getServer().broadcastMessage(ChatColor.GOLD + "╔══════════════════════════╗");
        getServer().broadcastMessage(ChatColor.GOLD + "║  " + ChatColor.YELLOW + "⚡ Скупщик обновил план!  " + ChatColor.GOLD + " ║");
        getServer().broadcastMessage(ChatColor.GOLD + "║  " + ChatColor.WHITE + "Активна: " + ChatColor.GREEN + pageNames[currentPage] + ChatColor.GOLD + "  ║");
        getServer().broadcastMessage(ChatColor.GOLD + "║  " + ChatColor.GRAY + "Подойдите к Скупщику на спавне" + ChatColor.GOLD + "  ║");
        getServer().broadcastMessage(ChatColor.GOLD + "╚══════════════════════════╝");
        getServer().broadcastMessage("");
        getLogger().info("BuyerBot: страница сменена на " + (currentPage + 1));
    }

    // ==================== GUI ====================

    private static final String GUI_TITLE_PREFIX = "\u00A72\u0421\u043a\u0443\u043f\u0449\u0438\u043a"; // §2Скупщик

    private Inventory buildGui() {
        String title = ChatColor.DARK_GREEN + "Скупщик" + ChatColor.GRAY + " — Стр. " + (currentPage + 1) + "/3";
        Inventory inv = getServer().createInventory(null, 9, title);
        List<ShopItem> page = pages.get(currentPage);
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, buildSlot(page.get(i), i));
        }
        return inv;
    }

    private ItemStack buildSlot(ShopItem item, int slot) {
        int remaining = item.maxSales - soldCounts[currentPage][slot];
        ItemStack stack = new ItemStack(item.material, item.amount);
        ItemMeta meta = stack.getItemMeta();
        List<String> lore = new ArrayList<>();

        if (remaining > 0) {
            meta.setDisplayName(ChatColor.GREEN + item.displayName);
            lore.add(ChatColor.GRAY + "Количество для продажи: " + ChatColor.WHITE + item.amount + " шт.");
            lore.add(ChatColor.GRAY + "Цена: " + ChatColor.YELLOW + "$" + item.price);
            lore.add(ChatColor.GRAY + "Осталось продаж: " + ChatColor.AQUA + remaining + ChatColor.DARK_GRAY + "/" + item.maxSales);
            lore.add("");
            lore.add(ChatColor.GREEN + "▶  Нажмите чтобы продать");
        } else {
            meta.setDisplayName(ChatColor.RED + item.displayName + ChatColor.DARK_RED + " [ИСЧЕРПАНО]");
            lore.add(ChatColor.GRAY + "Количество: " + ChatColor.WHITE + item.amount + " шт.");
            lore.add(ChatColor.GRAY + "Цена: " + ChatColor.YELLOW + "$" + item.price);
            lore.add(ChatColor.RED + "✗ Лимит исчерпан!");
            lore.add(ChatColor.GRAY + "Обновится через 4 часа");
        }
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    // ==================== СОБЫТИЯ ====================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (fakeNpc == null || !fakeNpc.isCreated()) return;
        Player p = event.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) fakeNpc.spawnFor(p);
        }, 20L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (fakeNpc == null || !fakeNpc.isCreated()) return;
        Player p = event.getPlayer();
        fakeNpc.forgetPlayer(p.getUniqueId());
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) fakeNpc.spawnFor(p);
        }, 20L);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (fakeNpc == null || !fakeNpc.isCreated()) return;
        Player p = event.getPlayer();
        fakeNpc.forgetPlayer(p.getUniqueId());
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && p.getWorld().getName().equals(fakeNpc.getLocationWorld()))
                fakeNpc.spawnFor(p);
        }, 60L); // 3 секунды — клиент успевает загрузить новый мир
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();
        if (!title.contains("Скупщик")) return;
        event.setCancelled(true);

        // Клик должен быть в верхнем инвентаре (наш GUI)
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(event.getView().getTopInventory())) return;

        int slot = event.getSlot();
        if (slot < 0 || slot >= 9) return;

        Player player = (Player) event.getWhoClicked();
        ShopItem item = pages.get(currentPage).get(slot);

        int remaining = item.maxSales - soldCounts[currentPage][slot];
        if (remaining <= 0) {
            player.sendMessage(ChatColor.RED + "✗ Лимит продаж для '" + item.displayName + "' исчерпан!");
            return;
        }

        int inInventory = countItems(player.getInventory(), item.material);
        if (inInventory < item.amount) {
            player.sendMessage(ChatColor.RED + "✗ Недостаточно предметов! Нужно: "
                    + ChatColor.YELLOW + item.amount + " " + item.displayName
                    + ChatColor.RED + ", у вас: " + ChatColor.YELLOW + inInventory);
            return;
        }

        removeItems(player.getInventory(), item.material, item.amount);
        economy.depositPlayer(player, item.price);
        soldCounts[currentPage][slot]++;
        saveData();

        player.sendMessage(ChatColor.GREEN + "✔ Продано: " + ChatColor.WHITE + item.amount + "x " + item.displayName
                + ChatColor.GREEN + " за " + ChatColor.YELLOW + "$" + item.price
                + ChatColor.GREEN + ". Баланс: " + ChatColor.YELLOW + "$" + String.format("%.2f", economy.getBalance(player)));

        // Обновить GUI
        List<ShopItem> page = pages.get(currentPage);
        for (int i = 0; i < 9; i++) {
            event.getView().getTopInventory().setItem(i, buildSlot(page.get(i), i));
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().contains("Скупщик")) {
            event.setCancelled(true);
        }
    }

    // ==================== УТИЛИТЫ ====================

    private int countItems(PlayerInventory inv, Material material) {
        int count = 0;
        for (ItemStack stack : inv.getStorageContents()) {
            if (stack != null && stack.getType() == material && !stack.hasItemMeta()) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private void removeItems(PlayerInventory inv, Material material, int amount) {
        int toRemove = amount;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && toRemove > 0; i++) {
            ItemStack stack = contents[i];
            if (stack != null && stack.getType() == material && !stack.hasItemMeta()) {
                if (stack.getAmount() <= toRemove) {
                    toRemove -= stack.getAmount();
                    contents[i] = null;
                } else {
                    stack.setAmount(stack.getAmount() - toRemove);
                    toRemove = 0;
                }
            }
        }
        inv.setStorageContents(contents);
    }

    // ==================== КОМАНДЫ ====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("buyerbot")) return false;
        if (!sender.hasPermission("buyerbot.admin")) {
            sender.sendMessage(ChatColor.RED + "Нет прав.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/buyerbot spawn — создать бота в текущей позиции");
            sender.sendMessage(ChatColor.YELLOW + "/buyerbot skin <ник> — установить скин игрока");
            sender.sendMessage(ChatColor.YELLOW + "/buyerbot page <1-3> — переключить страницу");
            sender.sendMessage(ChatColor.YELLOW + "/buyerbot rotate — принудительно сменить страницу");
            sender.sendMessage(ChatColor.YELLOW + "/buyerbot info — состояние");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "spawn":
                if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; }
                Player sp = (Player) sender;
                Location spLoc = sp.getLocation();
                getConfig().set("bot-location.world", spLoc.getWorld().getName());
                getConfig().set("bot-location.x", spLoc.getX());
                getConfig().set("bot-location.y", spLoc.getY());
                getConfig().set("bot-location.z", spLoc.getZ());
                getConfig().set("bot-location.yaw", (double) spLoc.getYaw());
                saveConfig();
                createNPCAt(spLoc, getConfig().getString("skin.texture"), getConfig().getString("skin.signature"));
                sender.sendMessage(ChatColor.GREEN + "Бот создан! Установи скин: /buyerbot skin <ник>");
                return true;
            case "skin":
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Использование: /buyerbot skin <ник>"); return true; }
                String skinTarget = args[1];
                if (!skinTarget.matches("[a-zA-Z0-9_]{1,16}")) { sender.sendMessage(ChatColor.RED + "Неверный ник."); return true; }
                sender.sendMessage(ChatColor.YELLOW + "Загружаю скин игрока " + skinTarget + "...");
                fetchSkinAsync(sender, skinTarget);
                return true;
            case "page":
                if (args.length < 2) { sender.sendMessage("Укажите номер страницы 1-3"); return true; }
                try {
                    int pg = Integer.parseInt(args[1]) - 1;
                    if (pg < 0 || pg > 2) { sender.sendMessage("Страница 1-3"); return true; }
                    currentPage = pg;
                    nextRotationTime = System.currentTimeMillis() + CYCLE_MS;
                    saveData();
                    sender.sendMessage(ChatColor.GREEN + "Страница переключена на " + (currentPage + 1));
                } catch (NumberFormatException e) { sender.sendMessage("Укажите число 1-3"); }
                return true;
            case "rotate":
                rotatePage();
                sender.sendMessage(ChatColor.GREEN + "Страница сменена принудительно.");
                return true;
            case "info":
                long ms = nextRotationTime - System.currentTimeMillis();
                long h = ms / 3600000, m = (ms % 3600000) / 60000;
                sender.sendMessage(ChatColor.YELLOW + "Страница: " + (currentPage + 1) + "/3");
                sender.sendMessage(ChatColor.YELLOW + "До смены: " + h + "ч " + m + "мин");
                for (int sl = 0; sl < 9; sl++) {
                    ShopItem it = pages.get(currentPage).get(sl);
                    int rem = it.maxSales - soldCounts[currentPage][sl];
                    sender.sendMessage(ChatColor.GRAY + "  [" + sl + "] " + it.displayName + ": " + rem + "/" + it.maxSales);
                }
                return true;
        }
        return false;
    }

    // ==================== Skin fetch ====================

    private void fetchSkinAsync(CommandSender sender, String username) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                // Step 1: get UUID by username
                URL url1 = new URL("https://api.mojang.com/users/profiles/minecraft/" + username);
                HttpURLConnection c1 = (HttpURLConnection) url1.openConnection();
                c1.setRequestProperty("Accept-Encoding", "identity");
                c1.setRequestProperty("User-Agent", "BuyerBot/1.0");
                c1.setConnectTimeout(5000);
                c1.setReadTimeout(5000);
                int code1 = c1.getResponseCode();
                if (code1 != 200) {
                    runSync(sender, ChatColor.RED + "Игрок '" + username + "' не найден (код " + code1 + ").");
                    c1.disconnect();
                    return;
                }
                String resp1 = new String(c1.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                c1.disconnect();
                getLogger().info("[BuyerBot] Mojang resp1: " + resp1);
                if (!resp1.contains("\"id\"")) {
                    runSync(sender, ChatColor.RED + "Не удалось получить UUID для '" + username + "' (ответ: " + resp1.substring(0, Math.min(resp1.length(), 80)) + ").");
                    return;
                }
                // Mojang API может возвращать "id" : "..." или "id":"..." — используем regex
                String rawUUID = resp1.split("\"id\"\\s*:\\s*\"")[1].split("\"")[0];
                String uuid = rawUUID.replaceAll("(.{8})(.{4})(.{4})(.{4})(.+)", "$1-$2-$3-$4-$5");

                // Step 2: get skin texture
                URL url2 = new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");
                HttpURLConnection c2 = (HttpURLConnection) url2.openConnection();
                c2.setRequestProperty("Accept-Encoding", "identity");
                c2.setRequestProperty("User-Agent", "BuyerBot/1.0");
                c2.setConnectTimeout(5000);
                c2.setReadTimeout(5000);
                int code2 = c2.getResponseCode();
                if (code2 != 200) {
                    runSync(sender, ChatColor.RED + "Не удалось загрузить скин (код " + code2 + ").");
                    c2.disconnect();
                    return;
                }
                String resp2 = new String(c2.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                c2.disconnect();
                if (!resp2.contains("\"value\"")) {
                    runSync(sender, ChatColor.RED + "У игрока '" + username + "' нет скина.");
                    return;
                }
                String texture = resp2.split("\"value\"\\s*:\\s*\"")[1].split("\"")[0];
                String signature = resp2.contains("\"signature\"")
                    ? resp2.split("\"signature\"\\s*:\\s*\"")[1].split("\"")[0]
                    : null;

                getServer().getScheduler().runTask(this, () -> {
                    getConfig().set("skin.texture",   texture);
                    getConfig().set("skin.signature", signature);
                    saveConfig();
                    if (fakeNpc != null && fakeNpc.isCreated()) {
                        fakeNpc.setSkin(texture, signature);
                        sender.sendMessage(ChatColor.GREEN + "✔ Скин '" + username + "' установлен!");
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "Скин сохранён. Создайте NPC: /buyerbot spawn");
                    }
                });
            } catch (Exception e) {
                runSync(sender, ChatColor.RED + "Ошибка загрузки скина: " + e.getMessage());
            }
        });
    }

    private void runSync(CommandSender target, String msg) {
        getServer().getScheduler().runTask(this, () -> target.sendMessage(msg));
    }

    // ==================== ShopItem ====================

    public static class ShopItem {
        public final Material material;
        public final String displayName;
        public final int amount;
        public final double price;
        public final int maxSales;

        public ShopItem(Material material, String displayName, int amount, double price, int maxSales) {
            this.material = material;
            this.displayName = displayName;
            this.amount = amount;
            this.price = price;
            this.maxSales = maxSales;
        }
    }
}
