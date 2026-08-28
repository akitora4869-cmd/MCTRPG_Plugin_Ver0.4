package Rin.TRPGCharacter;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InputManager {

    private final Plugin plugin;
    private final CharacterManager characterManager;
    private final SkillManager skillManager;
    private final Map<UUID, PendingInput> pending = new ConcurrentHashMap<>();

    public InputManager(Plugin plugin,
                        CharacterManager characterManager,
                        SkillManager skillManager) {
        this.plugin = plugin;
        this.characterManager = characterManager;
        this.skillManager = skillManager;
    }

    public void beginStat(Player player, String stat) {
        pending.put(
                player.getUniqueId(),
                new PendingInput(PendingInput.Type.STAT, stat, stat)
        );

        player.sendMessage(color("&6[TRPG] &f" + stat
                + " の新しい値をチャットに入力してください。"));
        player.sendMessage(color("&7キャンセルする場合は &fcancel &7と入力してください。"));
    }

    public void beginHpDamage(Player player) {
        beginAmount(player, PendingInput.Type.HP_DAMAGE, "HPダメージ量");
    }

    public void beginHpHeal(Player player) {
        beginAmount(player, PendingInput.Type.HP_HEAL, "HP回復量");
    }

    public void beginMpSpend(Player player) {
        beginAmount(player, PendingInput.Type.MP_SPEND, "MP消費量");
    }

    public void beginMpRecover(Player player) {
        beginAmount(player, PendingInput.Type.MP_RECOVER, "MP回復量");
    }

    private void beginAmount(Player player, PendingInput.Type type, String name) {
        pending.put(player.getUniqueId(), new PendingInput(type, "amount", name));
        player.sendMessage(color("&6[TRPG] &f" + name + "をチャットに入力してください。"));
        player.sendMessage(color("&7キャンセルする場合は &fcancel &7と入力してください。"));
    }

    public void beginCurrentHp(Player player) {
        pending.put(
                player.getUniqueId(),
                new PendingInput(PendingInput.Type.CURRENT_HP, "current_hp", "現在HP")
        );
        player.sendMessage(color("&6[TRPG] &f現在HPの新しい値をチャットに入力してください。"));
        player.sendMessage(color("&7キャンセルする場合は &fcancel &7と入力してください。"));
    }

    public void beginCurrentMp(Player player) {
        pending.put(
                player.getUniqueId(),
                new PendingInput(PendingInput.Type.CURRENT_MP, "current_mp", "現在MP")
        );
        player.sendMessage(color("&6[TRPG] &f現在MPの新しい値をチャットに入力してください。"));
        player.sendMessage(color("&7キャンセルする場合は &fcancel &7と入力してください。"));
    }

    public void beginSanLoss(Player player) {
        pending.put(
                player.getUniqueId(),
                new PendingInput(PendingInput.Type.SAN_LOSS, "san_loss", "SAN減少量")
        );
        player.sendMessage(color("&6[TRPG] &fSAN減少量を入力してください。"));
        player.sendMessage(color("&7例: 3  / キャンセルは &fcancel"));
    }

    public void beginCurrentSan(Player player) {
        pending.put(
                player.getUniqueId(),
                new PendingInput(PendingInput.Type.CURRENT_SAN, "current_san", "現在SAN")
        );

        player.sendMessage(color("&6[TRPG] &f現在SANの新しい値をチャットに入力してください。"));
        player.sendMessage(color("&7キャンセルする場合は &fcancel &7と入力してください。"));
    }

    public void beginSkill(Player player, String skillId) {
        SkillDefinition skill = skillManager.getSkill(skillId);
        if (skill == null) {
            player.sendMessage(color("&c技能が見つかりません。"));
            return;
        }

        pending.put(
                player.getUniqueId(),
                new PendingInput(PendingInput.Type.SKILL, skillId, skill.getName())
        );

        player.sendMessage(color("&6[TRPG] &f" + skill.getName()
                + " の新しい値をチャットに入力してください。"));
        player.sendMessage(color("&7キャンセルする場合は &fcancel &7と入力してください。"));
    }

    public void beginCharacterName(Player player) {
        pending.put(
                player.getUniqueId(),
                new PendingInput(PendingInput.Type.CHARACTER_NAME, "character_name", "キャラクター名")
        );
        player.sendMessage(color("&6[TRPG] &f新しいキャラクター名をチャットに入力してください。"));
        player.sendMessage(color("&7キャンセルする場合は &fcancel &7と入力してください。"));
    }

    public boolean hasPending(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    public void handleInput(Player player, String message) {
        PendingInput input = pending.get(player.getUniqueId());
        if (input == null) {
            return;
        }

        if (message.equalsIgnoreCase("cancel")) {
            pending.remove(player.getUniqueId());
            player.sendMessage(color("&7入力をキャンセルしました。"));
            return;
        }

        if (input.type() == PendingInput.Type.CHARACTER_NAME) {
            String name = message.trim();
            if (name.isEmpty()) {
                player.sendMessage(color("&cキャラクター名を入力してください。"));
                return;
            }
            if (name.length() > 32) {
                player.sendMessage(color("&cキャラクター名は32文字以内にしてください。"));
                return;
            }

            characterManager.setCharacterName(player, name);
            pending.remove(player.getUniqueId());
            player.sendMessage(color("&aキャラクター名を「" + name + "」に設定しました。"));
            plugin.getSidebarManager().updatePlayer(player);
            plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    () -> plugin.getBookManager().openSheet(player),
                    2L
            );
            return;
        }

        int value;
        try {
            value = Integer.parseInt(message.trim());
        } catch (NumberFormatException e) {
            player.sendMessage(color("&c数字を入力してください。キャンセルは cancel です。"));
            return;
        }

        int min = plugin.getConfig().getInt("input.min", 0);
        int max = plugin.getConfig().getInt("input.max", 999);

        if (value < min || value > max) {
            player.sendMessage(color("&c" + min + "～" + max + " の範囲で入力してください。"));
            return;
        }

        String completionMessage;

        switch (input.type()) {
            case STAT -> {
                characterManager.setStat(player, input.id(), value);
                completionMessage = "&a" + input.displayName() + " を " + value + " に設定しました。";
            }
            case SKILL -> {
                characterManager.setSkill(player, input.id(), value);
                completionMessage = "&a" + input.displayName() + " を " + value + " に設定しました。";
            }
            case CURRENT_SAN -> {
                characterManager.setCurrentSan(player, value);
                completionMessage = "&a現在SANを " + value + " に設定しました。";
            }
            case CURRENT_HP -> {
                characterManager.setCurrentHp(player, value);
                completionMessage = "&a現在HPを " + value + " に設定しました。";
            }
            case CURRENT_MP -> {
                characterManager.setCurrentMp(player, value);
                completionMessage = "&a現在MPを " + value + " に設定しました。";
            }
            case SAN_LOSS -> {
                int before = characterManager.getCurrentSan(player);
                int after = Math.max(0, before - value);
                characterManager.setCurrentSan(player, after);
                completionMessage = "&dSAN減少: &f" + before + " &7→ &f" + after
                        + " &7(-" + value + ")";
            }
            case HP_DAMAGE -> {
                int before = characterManager.getCurrentHp(player);
                int after = Math.max(0, before - value);
                characterManager.setCurrentHp(player, after);
                completionMessage = "&cHPダメージ: &f" + before + " &7→ &f" + after
                        + " &7(-" + value + ")";
            }
            case HP_HEAL -> {
                int before = characterManager.getCurrentHp(player);
                int maxHp = characterManager.getHp(player);
                int after = Math.min(maxHp, before + value);
                characterManager.setCurrentHp(player, after);
                completionMessage = "&aHP回復: &f" + before + " &7→ &f" + after
                        + " &7(+" + (after - before) + ")";
            }
            case MP_SPEND -> {
                int before = characterManager.getCurrentMp(player);
                int after = Math.max(0, before - value);
                characterManager.setCurrentMp(player, after);
                completionMessage = "&5MP消費: &f" + before + " &7→ &f" + after
                        + " &7(-" + value + ")";
            }
            case MP_RECOVER -> {
                int before = characterManager.getCurrentMp(player);
                int maxMp = characterManager.getMp(player);
                int after = Math.min(maxMp, before + value);
                characterManager.setCurrentMp(player, after);
                completionMessage = "&bMP回復: &f" + before + " &7→ &f" + after
                        + " &7(+" + (after - before) + ")";
            }
            default -> completionMessage = "&a更新しました。";
        }

        pending.remove(player.getUniqueId());

        player.sendMessage(color(completionMessage));
        plugin.getSidebarManager().updatePlayer(player);
        plugin.getHealthSyncManager().sync(player);

        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> plugin.getBookManager().openSheet(player),
                2L
        );
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
