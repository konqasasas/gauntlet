package konqasasas.gauntlet.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/** Plain text that is not a message key (course and template names written by builders). */
public final class Icons {
    public static Component text(String value) { return Component.text(value).decoration(TextDecoration.ITALIC,false); }
    private Icons() {}
}
