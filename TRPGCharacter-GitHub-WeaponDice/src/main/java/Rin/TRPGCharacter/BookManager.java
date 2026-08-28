package Rin.TRPGCharacter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class BookManager {

    private final Plugin plugin;
    private final CharacterManager characterManager;
    private final SkillManager skillManager;
    private final NamespacedKey sheetKey;

    public BookManager(Plugin plugin,
                       CharacterManager characterManager,
                       SkillManager skillManager) {
        this.plugin = plugin;
        this.characterManager = characterManager;
        this.skillManager = skillManager;
        this.sheetKey = new NamespacedKey(plugin, "character_sheet");
    }

    public void openSheet(Player player) {
        player.openBook(createSheet(player));
    }

    public void openRandomConfirm(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();

        meta.setTitle("能力値一括生成");
        meta.setAuthor(plugin.getConfig().getString("book.author", "TRPG System"));

        Component page = Component.text("能力値一括生成", NamedTextColor.DARK_RED)
                .decorate(TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text(
                        "現在の能力値を上書きして、CoC第6版標準式で新しく生成します。\n\n",
                        NamedTextColor.BLACK
                ))
                .append(Component.text(
                        "STR/CON/POW/DEX/APP: 3d6\nSIZ/INT: 2d6+6\nEDU: 3d6+3\n\n",
                        NamedTextColor.DARK_GRAY
                ))
                .append(button(
                        "[実行]",
                        NamedTextColor.DARK_GREEN,
                        "/status random",
                        "能力値を一括生成します"
                ))
                .append(Component.space())
                .append(button(
                        "[キャンセル]",
                        NamedTextColor.DARK_RED,
                        "/status",
                        "探索者シートへ戻ります"
                ));

        meta.addPages(page);
        book.setItemMeta(meta);
        player.openBook(book);
    }

    public ItemStack createSheet(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();

        meta.setTitle(plugin.getConfig().getString("book.title", "探索者シート"));
        meta.setAuthor(plugin.getConfig().getString("book.author", "TRPG System"));
        meta.getPersistentDataContainer().set(sheetKey, PersistentDataType.BYTE, (byte) 1);

        List<Component> pages = new ArrayList<>();
        pages.add(createStatsPage(player));
        pages.add(createDerivedPage(player));
        pages.add(createCompositePage());

        LinkedHashMap<String, List<SkillDefinition>> grouped = skillManager.groupByCategory();

        for (var entry : grouped.entrySet()) {
            List<SkillDefinition> skills = entry.getValue();

            for (int start = 0; start < skills.size(); start += 5) {
                int end = Math.min(start + 5, skills.size());
                pages.add(createSkillsPage(player, entry.getKey(), skills.subList(start, end)));
            }
        }

        meta.addPages(pages.toArray(Component[]::new));
        book.setItemMeta(meta);
        return book;
    }

    public boolean isCharacterSheet(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK) {
            return false;
        }

        if (!(item.getItemMeta() instanceof BookMeta meta)) {
            return false;
        }

        Byte value = meta.getPersistentDataContainer().get(sheetKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private Component createStatsPage(Player player) {
        Component page = title("基本能力値");

        page = page.append(
                Component.text("名前 " + characterManager.getCharacterName(player) + " ", NamedTextColor.BLACK)
        );
        page = page.append(button(
                "[変更]",
                NamedTextColor.BLUE,
                "/trpgedit name character",
                "キャラクター名を変更"
        ));
        page = page.append(Component.newline());

        page = page.append(button(
                "[能力値一括生成]",
                NamedTextColor.DARK_PURPLE,
                "/status random-confirm",
                "能力値一括生成の確認画面を開く"
        ));
        page = page.append(Component.newline()).append(Component.newline());

        for (String stat : characterManager.getStats()) {
            int value = characterManager.getStat(player, stat);

            page = page.append(
                    Component.text(stat + " " + value + " ", NamedTextColor.BLACK)
            );

            page = page.append(button(
                    "[変更]",
                    NamedTextColor.BLUE,
                    "/trpgedit stat " + stat,
                    stat + "の値を変更"
            ));

            page = page.append(Component.space());

            page = page.append(button(
                    "[判定]",
                    NamedTextColor.DARK_GREEN,
                    "/trpgroll stat " + stat,
                    stat + "×5で1d100判定"
            ));

            page = page.append(Component.newline());
        }

        return page;
    }

    private Component createDerivedPage(Player player) {
        Component page = title("派生値");

        page = page.append(
                Component.text("HP " + characterManager.getCurrentHp(player)
                        + " / " + characterManager.getHp(player) + " ", NamedTextColor.BLACK)
        );
        page = page.append(button(
                "[変更]",
                NamedTextColor.BLUE,
                "/trpgedit hp current",
                "現在HPの値を変更"
        ));
        page = page.append(Component.space());
        page = page.append(button(
                "[-]",
                NamedTextColor.DARK_RED,
                "/trpgedit hp damage",
                "HPダメージ量を入力"
        ));
        page = page.append(Component.space());
        page = page.append(button(
                "[+]",
                NamedTextColor.DARK_GREEN,
                "/trpgedit hp heal",
                "HP回復量を入力"
        ));
        page = page.append(Component.newline());

        page = page.append(
                Component.text("MP " + characterManager.getCurrentMp(player)
                        + " / " + characterManager.getMp(player) + " ", NamedTextColor.BLACK)
        );
        page = page.append(button(
                "[変更]",
                NamedTextColor.BLUE,
                "/trpgedit mp current",
                "現在MPの値を変更"
        ));
        page = page.append(Component.space());
        page = page.append(button(
                "[-]",
                NamedTextColor.DARK_RED,
                "/trpgedit mp spend",
                "MP消費量を入力"
        ));
        page = page.append(Component.space());
        page = page.append(button(
                "[+]",
                NamedTextColor.DARK_GREEN,
                "/trpgedit mp recover",
                "MP回復量を入力"
        ));
        page = page.append(Component.newline());

        page = page.append(line("SAN初期値", characterManager.getSan(player)));

        page = page.append(
                Component.text("現在SAN " + characterManager.getCurrentSan(player) + " ", NamedTextColor.BLACK)
        );
        page = page.append(button(
                "[変更]",
                NamedTextColor.BLUE,
                "/trpgedit san current",
                "現在SANの値を変更"
        ));
        page = page.append(Component.space());
        page = page.append(button(
                "[判定]",
                NamedTextColor.DARK_GREEN,
                "/trpgroll san current",
                "現在SANで1d100判定"
        ));
        page = page.append(Component.space());
        page = page.append(button(
                "[減少]",
                NamedTextColor.DARK_RED,
                "/trpgedit sanloss apply",
                "SAN減少量を入力"
        ));
        page = page.append(Component.newline()).append(Component.newline());

        page = page.append(derivedCheckLine(player, "アイデア", "idea"));
        page = page.append(derivedCheckLine(player, "幸運", "luck"));
        page = page.append(derivedCheckLine(player, "知識", "knowledge"));

        page = page.append(Component.newline())
                .append(Component.text(
                        "※HP=(CON+SIZ)/2切上\nMP=POW\nSAN初期値=POW×5",
                        NamedTextColor.DARK_GRAY
                ));

        return page;
    }

    private Component createCompositePage() {
        Component page = title("複合技能");

        page = page.append(Component.text("医学＋応急手当 ", NamedTextColor.BLACK))
                .append(button("[判定]", NamedTextColor.DARK_GREEN,
                        "/trpgcombo medicine_firstaid", "段階判定を行います"))
                .append(Component.newline()).append(Component.newline());

        page = page.append(Component.text("目星＋聞き耳 ", NamedTextColor.BLACK))
                .append(button("[判定]", NamedTextColor.DARK_GREEN,
                        "/trpgcombo spot_listen", "段階判定を行います"))
                .append(Component.newline()).append(Component.newline());

        page = page.append(Component.text("隠れる＋忍び歩き ", NamedTextColor.BLACK))
                .append(button("[判定]", NamedTextColor.DARK_GREEN,
                        "/trpgcombo hide_sneak", "段階判定を行います"))
                .append(Component.newline()).append(Component.newline());

        page = page.append(Component.text("登攀＋跳躍 ", NamedTextColor.BLACK))
                .append(button("[判定]", NamedTextColor.DARK_GREEN,
                        "/trpgcombo climb_jump", "段階判定を行います"));

        return page;
    }

    private Component createSkillsPage(Player player,
                                       String category,
                                       List<SkillDefinition> skills) {
        Component page = title(category);

        for (SkillDefinition skill : skills) {
            int value = skillManager.getSkillValue(player, skill.getId());

            page = page.append(
                    Component.text(skill.getName() + " " + value + " ", NamedTextColor.BLACK)
            );

            page = page.append(button(
                    "[変更]",
                    NamedTextColor.BLUE,
                    "/trpgedit skill " + skill.getId(),
                    skill.getName() + "の値を変更"
            ));

            page = page.append(Component.space());

            page = page.append(button(
                    "[判定]",
                    NamedTextColor.DARK_GREEN,
                    "/trpgroll skill " + skill.getId(),
                    skill.getName() + "で1d100判定"
            ));

            page = page.append(Component.newline()).append(Component.newline());
        }

        return page;
    }

    private Component derivedCheckLine(Player player, String label, String id) {
        int value = characterManager.getDerived(player, id);

        return Component.text(label + " " + value + " ", NamedTextColor.BLACK)
                .append(button(
                        "[判定]",
                        NamedTextColor.DARK_GREEN,
                        "/trpgroll derived " + id,
                        label + "で1d100判定"
                ))
                .append(Component.newline());
    }

    private Component title(String text) {
        return Component.text(text, NamedTextColor.DARK_BLUE)
                .decorate(TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.newline());
    }

    private Component line(String label, int value) {
        return Component.text(label + " : " + value, NamedTextColor.BLACK)
                .append(Component.newline());
    }

    private Component button(String text,
                             NamedTextColor color,
                             String command,
                             String hover) {
        return Component.text(text, color)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(
                        Component.text(hover, NamedTextColor.GRAY)
                ));
    }
}
