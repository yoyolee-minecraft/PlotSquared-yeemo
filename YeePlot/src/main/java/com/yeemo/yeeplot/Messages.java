package com.yeemo.yeeplot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;

/**
 * 讀取 config.yml 的 messages 區段，支援 &amp; 色碼與 {key} 佔位符。
 */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private ConfigurationSection section;

    public Messages(ConfigurationSection section) {
        this.section = section;
    }

    /**
     * /plot reload 時替換訊息內容，讓已持有此物件的監聽器也能拿到新訊息。
     */
    public void update(ConfigurationSection section) {
        this.section = section;
    }

    public String format(String key, String... placeholders) {
        return fill(raw(key), placeholders);
    }

    private String raw(String key) {
        String raw = section == null ? null : section.getString(key);
        return raw == null ? key : raw;
    }

    private static String fill(String raw, String... placeholders) {
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private String prefix() {
        return ChatColor.translateAlternateColorCodes('&', section == null ? "" : section.getString("prefix", ""));
    }

    public Component component(String key, String... placeholders) {
        return LEGACY.deserialize(format(key, placeholders));
    }

    /**
     * 一段可以點擊的文字：點擊執行 command，滑鼠移上去顯示 hoverKey 的內容。
     */
    public Component clickable(String key, String hoverKey, String command, String... placeholders) {
        return component(key, placeholders)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(component(hoverKey, placeholders)));
    }

    /**
     * 一組連續的訊息：只有第一行顯示 prefix，之後的行用空白補到與第一行的內容對齊。
     */
    public Block block(CommandSender sender) {
        return new Block(sender);
    }

    public void sendClickable(CommandSender sender, String key, String hoverKey, String command, String... placeholders) {
        block(sender).sendClickable(key, hoverKey, command, placeholders);
    }

    public void sendLine(CommandSender sender, Component... parts) {
        block(sender).sendLine(parts);
    }

    public void sendRich(CommandSender sender, String key, Map<String, Component> components, String... placeholders) {
        block(sender).sendRich(key, components, placeholders);
    }

    /**
     * 多行訊息也只有第一行顯示 prefix。
     */
    public void send(CommandSender sender, String key, String... placeholders) {
        block(sender).send(key, placeholders);
    }

    public final class Block {

        private final CommandSender sender;
        private boolean started;

        private Block(CommandSender sender) {
            this.sender = sender;
        }

        /**
         * 這一行開頭要接的文字：第一行是 prefix，之後是同樣寬度的空白，並延續 prefix 結尾的顏色。
         */
        private String lead() {
            String prefix = prefix();
            if (!started) {
                started = true;
                return prefix;
            }
            int width = section == null ? 0 : section.getInt("prefix-width", 0);
            return padding(width > 0 ? width : width(prefix)) + ChatColor.getLastColors(prefix);
        }

        public void send(String key, String... placeholders) {
            for (String line : format(key, placeholders).split("\n")) {
                sender.sendMessage(lead() + line);
            }
        }

        public void sendClickable(String key, String hoverKey, String command, String... placeholders) {
            sendLine(clickable(key, hoverKey, command, placeholders));
        }

        /**
         * 送出一行由多段文字組成的訊息。每段各自帶顏色，不會延續 prefix 的顏色。
         */
        public void sendLine(Component... parts) {
            TextComponent.Builder line = Component.text().append(LEGACY.deserialize(lead()));
            for (Component part : parts) {
                line.append(part);
            }
            sender.sendMessage(line.build());
        }

        /**
         * 與 send 相同，但 components 裡的佔位符換成元件（例如可點擊的按鈕）。
         */
        public void sendRich(String key, Map<String, Component> components, String... placeholders) {
            // 先在樣板上找出元件的位置再填入文字，避免別名之類的文字剛好含有 {trusted} 而被換掉
            for (String line : raw(key).split("\n")) {
                TextComponent.Builder builder = Component.text();
                String rest = line;
                // 行首與元件後面的文字要延續前面的顏色，所以接在下一段文字前面一起轉換
                String carry = lead();
                while (true) {
                    int at = -1;
                    String found = null;
                    for (String name : components.keySet()) {
                        int index = rest.indexOf("{" + name + "}");
                        if (index >= 0 && (at < 0 || index < at)) {
                            at = index;
                            found = name;
                        }
                    }
                    if (found == null) {
                        builder.append(LEGACY.deserialize(carry + fill(rest, placeholders)));
                        break;
                    }
                    String before = carry + fill(rest.substring(0, at), placeholders);
                    builder.append(LEGACY.deserialize(before));
                    builder.append(components.get(found));
                    carry = ChatColor.getLastColors(before);
                    rest = rest.substring(at + found.length() + 2);
                }
                sender.sendMessage(builder.build());
            }
        }

    }

    // ---------------------------------------------------------------- 對齊

    /**
     * 文字在原版預設字型中的寬度（像素，含字距）。粗體每個字多 1。
     * 中日韓文字與全形標點是 9，其餘沒列出的字當成 6。
     */
    static int width(String text) {
        int width = 0;
        boolean bold = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(++i));
                if (code == 'l') {
                    bold = true;
                } else if ("0123456789abcdefr".indexOf(code) >= 0) {
                    bold = false;
                }
                continue;
            }
            width += charWidth(c) + (bold ? 1 : 0);
        }
        return width;
    }

    private static int charWidth(char c) {
        if (c >= 0x2E80) {
            return 9;
        }
        return switch (c) {
            case '!', '\'', ',', '.', ':', ';', '|', 'i' -> 2;
            case '`', 'l' -> 3;
            case ' ', '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
            case '<', '>', 'f', 'k' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }

    /**
     * 指定寬度的空白：一般空白 4 像素、粗體空白 5 像素，兩種搭配起來 12 以上的寬度都能剛好湊到。
     */
    static String padding(int pixels) {
        for (int bold = 0; bold < 4; bold++) {
            int rest = pixels - bold * 5;
            if (rest >= 0 && rest % 4 == 0) {
                return "\u00a7r" + " ".repeat(rest / 4) + (bold > 0 ? "\u00a7l" + " ".repeat(bold) + "\u00a7r" : "");
            }
        }
        return "\u00a7r" + " ".repeat(Math.round(pixels / 4f));
    }

}
