package Rin.TRPGCharacter;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Random;

public class CombatManager implements Listener {

    private final Plugin plugin;
    private final CharacterManager characterManager;
    private final SkillManager skillManager;
    private final WeaponManager weaponManager;
    private final ArmorManager armorManager;
    private final Random random = new Random();

    public CombatManager(Plugin plugin,
                         CharacterManager characterManager,
                         SkillManager skillManager,
                         WeaponManager weaponManager,
                         ArmorManager armorManager) {
        this.plugin = plugin;
        this.characterManager = characterManager;
        this.skillManager = skillManager;
        this.weaponManager = weaponManager;
        this.armorManager = armorManager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }

        // 手掛かり用ArmorStandなどは既存の仕組みを優先する。
        if (target instanceof ArmorStand) {
            return;
        }

        if (!weaponManager.isEnabled()) {
            return;
        }

        WeaponDefinition weapon = weaponManager.resolve(attacker);
        if (weapon == null) {
            // weapons.yml に登録していないアイテムは従来のMinecraft攻撃を使用。
            return;
        }

        event.setCancelled(true);

        if (plugin.getTimeStopManager().isStopped()) {
            attacker.sendMessage(color("&5[時間停止] &7現在は攻撃できません。"));
            return;
        }

        if (attacker.getGameMode() == GameMode.SPECTATOR
                || characterManager.isDeadCharacter(attacker)) {
            return;
        }

        if (!characterManager.hasConfiguredStats(attacker)) {
            attacker.sendMessage(color("&c探索者能力値が設定されていないため、TRPG戦闘判定を行えません。"));
            return;
        }

        if (target instanceof Player targetPlayer) {
            if (!weaponManager.allowPvp()) {
                attacker.sendMessage(color("&c探索者同士の攻撃は無効です。"));
                return;
            }

            if (targetPlayer.getGameMode() == GameMode.SPECTATOR
                    || characterManager.isDeadCharacter(targetPlayer)) {
                return;
            }
        }

        int skillValue = weaponManager.getSkillValue(attacker, weapon);

        plugin.getDiceSoundManager().playRollSequence(attacker, () -> {
            if (!attacker.isOnline() || target.isDead() || !target.isValid()) {
                return;
            }

            int roll = random.nextInt(100) + 1;
            CheckResult result = CheckResult.evaluate(roll, skillValue);

            attacker.sendMessage(color(
                    "&6[攻撃判定] &f" + weapon.name()
                            + " &7/ 技能 &b" + getSkillName(weapon.skillId())
                            + " &7" + skillValue
                            + " &7/ 1d100:&e" + roll
                            + " &7→ " + result.color() + result.label()
            ));

            plugin.getDiceSoundManager().playResultSound(attacker, result);

            if (!result.isSuccess()) {
                if (result == CheckResult.FUMBLE) {
                    attacker.playSound(attacker.getLocation(),
                            Sound.ENTITY_ITEM_BREAK, 0.8f, 0.7f);
                }
                return;
            }

            DamageRoll damageRoll = calculateDamage(attacker, weapon, result);

            if (target instanceof Player targetPlayer) {
                applyToPlayer(attacker, targetPlayer, weapon, result, damageRoll);
            } else {
                applyToMob(attacker, target, weapon, result, damageRoll);
            }
        });
    }

    private DamageRoll calculateDamage(Player attacker,
                                       WeaponDefinition weapon,
                                       CheckResult result) {
        int base;
        int db = 0;

        if (result == CheckResult.CRITICAL && weaponManager.criticalMaxDamage()) {
            base = weaponManager.maxWeaponDamage(weapon);
            if (weapon.damageBonus()) {
                db = weaponManager.maxDamageBonus(attacker);
            }
        } else {
            base = weaponManager.rollWeaponDamage(weapon);
            if (weapon.damageBonus()) {
                db = weaponManager.rollDamageBonus(attacker);
            }
        }

        int special = result == CheckResult.SPECIAL
                ? weaponManager.specialBonus()
                : 0;

        int total = Math.max(0, base + db + special);
        return new DamageRoll(base, db, special, total);
    }

    private void applyToPlayer(Player attacker,
                               Player target,
                               WeaponDefinition weapon,
                               CheckResult result,
                               DamageRoll damageRoll) {
        int armor = armorManager.getArmor(target);
        int finalDamage = Math.max(0, damageRoll.total() - armor);

        int before = characterManager.getCurrentHp(target);
        int after = Math.max(0, before - finalDamage);

        characterManager.setCurrentHp(target, after);

        String formula = damageFormula(attacker, weapon, result, damageRoll);
        attacker.sendMessage(color(
                "&6[攻撃ダメージ] &f" + weapon.name()
                        + " &7" + formula
                        + " &7→ &c" + damageRoll.total()
                        + " &7/ 相手装甲 &b" + armor
                        + " &7/ 最終 &c" + finalDamage
        ));

        target.sendMessage(color(
                "&c[被ダメージ] &f" + attacker.getName()
                        + " &7の" + weapon.name()
                        + " → &c" + finalDamage
                        + " &7/ HP &f" + before + " → " + after
        ));

        plugin.getSidebarManager().updatePlayer(target);

        if (after <= 0) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (target.isOnline() && !target.isDead()) {
                    target.setHealth(0.0);
                }
            });
        } else {
            plugin.getHealthSyncManager().sync(target);
        }
    }

    private void applyToMob(Player attacker,
                            LivingEntity target,
                            WeaponDefinition weapon,
                            CheckResult result,
                            DamageRoll damageRoll) {
        double before = target.getHealth();
        double finalDamage = Math.max(0, damageRoll.total());
        double after = Math.max(0.0, before - finalDamage);

        target.setHealth(after);

        attacker.sendMessage(color(
                "&6[攻撃ダメージ] &f" + weapon.name()
                        + " &7" + damageFormula(attacker, weapon, result, damageRoll)
                        + " &7→ &c" + damageRoll.total()
                        + "ダメージ"
        ));
    }

    private String damageFormula(Player attacker,
                                 WeaponDefinition weapon,
                                 CheckResult result,
                                 DamageRoll damageRoll) {
        StringBuilder text = new StringBuilder();

        if (result == CheckResult.CRITICAL && weaponManager.criticalMaxDamage()) {
            text.append(weapon.damage()).append("(最大値)");
        } else {
            text.append(weapon.damage());
        }

        if (weapon.damageBonus()) {
            text.append(" + DB(")
                    .append(weaponManager.getDamageBonusLabel(attacker))
                    .append(")");
        }

        if (damageRoll.specialBonus() > 0) {
            text.append(" + Special(")
                    .append(damageRoll.specialBonus())
                    .append(")");
        }

        return text.toString();
    }

    private String getSkillName(String skillId) {
        SkillDefinition skill = skillManager.getSkill(skillId);
        return skill == null ? skillId : skill.getName();
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private record DamageRoll(int base, int db, int specialBonus, int total) {
    }
}
