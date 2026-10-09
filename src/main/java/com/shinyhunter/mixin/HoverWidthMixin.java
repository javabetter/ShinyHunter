package com.shinyhunter.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.shinyhunter.WideHovers;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Chat hover text is wrapped at {@code max(guiWidth / 2, 200)} pixels. The {@code /sparkling}
 * hover is a table laid out with its own line breaks, and wrapping it mid-row scrambles the
 * columns, so for those hovers (and only those) the wrap width is made wide enough for the
 * longest line. Every other hover is left exactly as vanilla wraps it.
 */
@Mixin(GuiGraphicsExtractor.class)
public class HoverWidthMixin {

    @WrapOperation(method = "componentHoverEffect", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"))
    private List<FormattedCharSequence> shinyhunter$noWrapForTables(Font font, FormattedText text, int width,
                                                                    Operation<List<FormattedCharSequence>> original) {
        return original.call(font, text, WideHovers.isWide(text) ? Math.max(width, 4000) : width);
    }
}
