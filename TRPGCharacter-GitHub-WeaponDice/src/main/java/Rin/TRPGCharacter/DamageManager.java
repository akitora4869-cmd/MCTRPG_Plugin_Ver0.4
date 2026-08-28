package Rin.TRPGCharacter;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.util.Random;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DamageManager implements Listener {

    private final Plugin plugin;
    private final CharacterManager characterManager;
    private final ArmorManager armorManager;
    private final Random random = new Random();
    private final File file;
    private YamlConfiguration config;

    // Player UUID -> damage cause -> next allowed timestamp(ms)
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    private static final Pattern DICE =
            Pattern.compile("^(\\d+)[dD](\\d+)(?:\\+(\\d+))?$");

    public DamageManager(Plugin plugin, CharacterManager characterManager, ArmorManager armorManager) {
        this.plugin = plugin;
        this.characterManager = characterManager;
        this.armorManager = armorManager;
        this.file = new File(plugin.getDataFolder(), "damage.yml");

        if (!file.exists()) {
            plugin.saveResource("damage.yml", false);
        }

        reload();
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (plugin.getTimeStopManager().isStopped()) {
            event.setCancelled(true);
            return;
        }

        if (!config.getBoolean("enabled", true)) {
            return;
        }

        if (!characterManager.hasConfiguredStats(player)) {
            return;
        }

        if (characterManager.isDeadCharacter(player)) {
            event.setCancelled(true);
            return;
        }

        if (isOnCooldown(player, event)) {
            event.setCancelled(true);
            return;
        }

        DamageDefinition definition = resolveDamage(event);

        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            definition = resolveFallDamage(player);
        }

        int rolledDamage = Math.max(0, roll(definition.expression()));
        boolean armorApplies = resolveArmorApplies(event);
        int armor = armorApplies ? armorManager.getArmor(player) : 0;
        int damage = Math.max(0, rolledDamage - armor);

        event.setCancelled(true);

        int before = characterManager.getCurrentHp(player);
        int after = Math.max(0, before - damage);

        characterManager.setCurrentHp(player, after);
        armCooldown(player, event);

        String armorText = armorApplies
                ? " &7/ 装甲 &b" + armor + " &7/ 最終 &c" + damage
                : " &7/ 装甲無効 / 最終 &c" + damage;

        player.sendMessage(color(
                "&6[ダメージ] &f" + definition.name()
                        + " &7" + definition.expression()
                        + " → &c" + rolledDamage
                        + armorText
                        + " &7/ HP &f" + before + " → " + after
        ));

        plugin.getSidebarManager().updatePlayer(player);

        if (after <= 0) {
            // Minecraft本体の死亡処理を発生させる。
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && !player.isDead()) {
                    player.setHealth(0.0);
                }
            });
        } else {
            plugin.getHealthSyncManager().sync(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNaturalRegeneration(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        if (!config.getBoolean("disable-natural-regeneration", true)) {
            return;
        }

        if (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {
            event.setCancelled(true);
        }
    }

    private boolean isOnCooldown(Player player, EntityDamageEvent event) {
        long seconds = getCooldownSeconds(event);
        if (seconds <= 0) {
            return false;
        }

        String key = cooldownKey(event);
        long now = System.currentTimeMillis();

        Map<String, Long> playerMap = cooldowns.get(player.getUniqueId());
        if (playerMap == null) {
            return false;
        }

        long nextAllowed = playerMap.getOrDefault(key, 0L);
        return now < nextAllowed;
    }

    private void armCooldown(Player player, EntityDamageEvent event) {
        long seconds = getCooldownSeconds(event);
        if (seconds <= 0) {
            return;
        }

        String key = cooldownKey(event);
        long nextAllowed = System.currentTimeMillis() + (seconds * 1000L);

        cooldowns
                .computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(key, nextAllowed);
    }

    private long getCooldownSeconds(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();

        // FIRE と FIRE_TICK はMinecraft上で交互に発生することがあるため、
        // 炎上系として同じクールタイムを共有する。
        if (cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK) {
            long fireTick = config.getLong("environment.FIRE_TICK.cooldown-seconds", 3L);
            return config.getLong("environment.FIRE.cooldown-seconds", fireTick);
        }

        String env = "environment." + cause.name();
        return config.getLong(env + ".cooldown-seconds", 0L);
    }

    private String cooldownKey(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();

        if (cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK) {
            return "BURN";
        }

        return cause.name();
    }

    private DamageDefinition resolveFallDamage(Player player) {
        double distance = player.getFallDistance();
        String base = "environment.FALL";
        String name = config.getString(base + ".name", "落下");

        List<?> entries = config.getList(base + ".distance");
        if (entries == null || entries.isEmpty()) {
            return new DamageDefinition(name, "0");
        }

        for (Object object : entries) {
            if (!(object instanceof Map<?, ?> map)) {
                continue;
            }

            double min = number(map.get("min"), 0.0);
            double max = map.containsKey("max")
                    ? number(map.get("max"), Double.MAX_VALUE)
                    : Double.MAX_VALUE;

            if (distance >= min && distance <= max) {
                Object damage = map.get("damage");
                return new DamageDefinition(
                        name + " (" + String.format("%.1f", distance) + "ブロック)",
                        damage == null ? "0" : String.valueOf(damage)
                );
            }
        }

        return new DamageDefinition(
                name + " (" + String.format("%.1f", distance) + "ブロック)",
                "0"
        );
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }

        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }

        return fallback;
    }

    private boolean resolveArmorApplies(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity source = resolveSourceEntity(byEntity.getDamager());

            if (source != null) {
                String base = "mobs." + source.getType().name();
                if (config.contains(base + ".armor")) {
                    return config.getBoolean(base + ".armor", true);
                }
            }
        }

        String env = "environment." + event.getCause().name();
        if (config.contains(env + ".armor")) {
            return config.getBoolean(env + ".armor", false);
        }

        // モブの直接/飛び道具攻撃は既定で装甲有効。
        // 環境ダメージはdamage.ymlで明示した場合のみ有効。
        return event instanceof EntityDamageByEntityEvent;
    }

    private DamageDefinition resolveDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity source = resolveSourceEntity(byEntity.getDamager());

            if (source != null) {
                String type = source.getType().name();
                String base = "mobs." + type;

                if (config.contains(base + ".damage")) {
                    return new DamageDefinition(
                            config.getString(base + ".name", type),
                            config.getString(base + ".damage", "1d3")
                    );
                }
            }
        }

        String cause = event.getCause().name();
        String env = "environment." + cause;

        if (config.contains(env + ".damage")) {
            return new DamageDefinition(
                    config.getString(env + ".name", cause),
                    config.getString(env + ".damage", "1d3")
            );
        }

        return new DamageDefinition(
                config.getString("default.name", "攻撃"),
                config.getString("default.damage", "1d3")
        );
    }

    private Entity resolveSourceEntity(Entity damager) {
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Entity entity) {
                return entity;
            }
        }

        return damager;
    }

    private int roll(String expression) {
        String value = expression.trim();

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
        }

        Matcher matcher = DICE.matcher(value);
        if (!matcher.matches()) {
            plugin.getLogger().warning("damage.yml のダイス式を解釈できません: " + expression);
            return 0;
        }

        int count = Integer.parseInt(matcher.group(1));
        int sides = Integer.parseInt(matcher.group(2));
        int bonus = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));

        if (count < 1 || count > 100 || sides < 1 || sides > 100000) {
            return 0;
        }

        int total = bonus;
        for (int i = 0; i < count; i++) {
            total += random.nextInt(sides) + 1;
        }

        return total;
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
