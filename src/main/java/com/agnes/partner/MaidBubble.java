package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;

/**
 * The only place that puts words on screen.
 *
 * Touhou Little Maid already knows how to draw a speech bubble above a maid and sync it to every
 * nearby client, so the companion uses that instead of inventing its own chat spam. Spoken lines use
 * the native text bubble; her reasoning uses the native "thinking" bubble (grey italic text with the
 * yin-yang orb), which is exactly the distinction the character needs: what she says out loud versus
 * what she is turning over in her head.
 *
 * All calls happen on the server thread. Every entry point is defensive: a line of dialogue must
 * never be able to break a tick.
 */
final class MaidBubble {
    private MaidBubble() {}

    /** Her spoken line, in the ordinary speech bubble. Returns false when nothing was shown. */
    static boolean speak(EntityMaid maid, String text) {
        if (maid == null || text == null || text.isBlank()) return false;
        try {
            ChatBubbleManager bubbles = maid.getChatBubbleManager();
            if (bubbles == null) return false;
            bubbles.addTextChatBubble(MaidPersonality.clean(text, 200));
            return true;
        } catch (RuntimeException unsupported) { return false; }
    }

    /**
     * Her thoughts, in the native thinking bubble. The bubble key is remembered per maid so an older
     * thought can be replaced instead of piling up.
     */
    static boolean think(EntityMaid maid, String text) {
        if (maid == null || text == null || text.isBlank()) return false;
        try {
            ChatBubbleManager bubbles = maid.getChatBubbleManager();
            if (bubbles == null) return false;
            clearThinking(maid);
            long id = bubbles.addThinkingText(MaidPersonality.clean(text, 120));
            MaidBridge.mind(maid).putLong("ThinkBubble", id);
            return true;
        } catch (RuntimeException unsupported) { return false; }
    }


    /**
     * A bubble that only appears when the previous one with the same key has already expired. Used
     * for the low-priority narration so a fast loop cannot machine-gun identical bubbles.
     */
    static boolean speakIfExpired(EntityMaid maid, String key, String text, long ticks) {
        if (maid == null || text == null || text.isBlank()) return false;
        try {
            ChatBubbleManager bubbles = maid.getChatBubbleManager();
            if (bubbles == null) return false;
            CompoundTag mind = MaidBridge.mind(maid);
            long previous = mind.getLong("Bubble." + key);
            long id = bubbles.addTextChatBubbleIfTimeout(MaidPersonality.clean(text, 200), previous);
            if (id != previous) mind.putLong("Bubble." + key, id);
            return true;
        } catch (RuntimeException unsupported) { return false; }
    }

    /** Removes the current thinking bubble, e.g. once the plan it was describing has arrived. */
    static void clearThinking(EntityMaid maid) {
        try {
            CompoundTag mind = MaidBridge.mind(maid);
            if (!mind.contains("ThinkBubble")) return;
            long id = mind.getLong("ThinkBubble");
            mind.putLong("ThinkBubble", -1L);
            if (id < 0 || maid.getChatBubbleManager() == null) return;
            maid.getChatBubbleManager().removeChatBubble(id);
        } catch (RuntimeException ignored) { /* cosmetic only */ }
    }
}
