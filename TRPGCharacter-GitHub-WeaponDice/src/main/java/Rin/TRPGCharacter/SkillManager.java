package Rin.TRPGCharacter;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SkillManager {

    private final Plugin plugin;
    private final CharacterManager characterManager;
    private final File file;
    private final LinkedHashMap<String, SkillDefinition> skills = new LinkedHashMap<>();

    private static final Pattern FORMULA =
            Pattern.compile("^(STR|CON|POW|DEX|APP|SIZ|INT|EDU)\\s*\\*\\s*(\\d+)$",
                    Pattern.CASE_INSENSITIVE);

    public SkillManager(Plugin plugin, CharacterManager characterManager) {
        this.plugin = plugin;
        this.characterManager = characterManager;
        this.file = new File(plugin.getDataFolder(), "skills.yml");

        if (!file.exists()) {
            plugin.saveResource("skills.yml", false);
        }

        reload();
    }

    public void reload() {
        skills.clear();

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("skills");

        if (section == null) {
            plugin.getLogger().warning("skills.yml に skills: セクションがありません。");
            return;
        }

        for (String id : section.getKeys(false)) {
            String base = "skills." + id;
            String name = config.getString(base + ".name", id);
            Object rawDefault = config.get(base + ".default", 0);
            String defaultValue = String.valueOf(rawDefault);
            String category = config.getString(base + ".category", "その他");

            skills.put(id, new SkillDefinition(id, name, defaultValue, category));
        }
    }

    public Collection<SkillDefinition> getAllSkills() {
        return Collections.unmodifiableCollection(skills.values());
    }

    public SkillDefinition getSkill(String id) {
        return skills.get(id);
    }

    public boolean hasSkill(String id) {
        return skills.containsKey(id);
    }

    public int getSkillValue(Player player, String id) {
        Integer stored = characterManager.getStoredSkill(player, id);
        if (stored != null) {
            return stored;
        }

        SkillDefinition skill = skills.get(id);
        if (skill == null) {
            return 0;
        }

        return resolveDefault(player, skill.getDefaultValue());
    }

    private int resolveDefault(Player player, String raw) {
        String value = raw.trim();

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
        }

        Matcher matcher = FORMULA.matcher(value);
        if (matcher.matches()) {
            String stat = matcher.group(1).toUpperCase(Locale.ROOT);
            int multiplier = Integer.parseInt(matcher.group(2));
            return characterManager.getStat(player, stat) * multiplier;
        }

        plugin.getLogger().warning("解釈できない技能初期値: " + raw);
        return 0;
    }

    public LinkedHashMap<String, List<SkillDefinition>> groupByCategory() {
        LinkedHashMap<String, List<SkillDefinition>> grouped = new LinkedHashMap<>();

        for (SkillDefinition skill : skills.values()) {
            grouped.computeIfAbsent(skill.getCategory(), k -> new ArrayList<>()).add(skill);
        }

        return grouped;
    }
}
