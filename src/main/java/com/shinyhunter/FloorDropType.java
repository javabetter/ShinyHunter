package com.shinyhunter;

/**
 * The kinds of floor drop the spawn order tells apart ({@link FloorDropLog#predict}), each with the
 * vanilla item its stand-in shows and its own settings: shown or not, colour, and how many picked up
 * this run before the rest are hidden.
 */
public enum FloorDropType {
    LIME_GEM("Lime Gem", "minecraft:lime_dye"),
    PURPLE_GEM("Purple Gem", "minecraft:purple_dye"),
    ORANGE_GEM("Orange Gem", "minecraft:orange_dye"),
    WRIGGLEWORM("Wriggleworm", "minecraft:string"),
    YOGI_BERRY("Yogi Berry", "minecraft:sweet_berries"),
    BAG_OF_SEEDS("Bag of Seeds", "minecraft:wheat_seeds"),
    SOOTHING_INCENSE("Soothing Incense", "minecraft:blaze_powder"),
    SHINING_COIN("Shining Coin", "minecraft:gold_nugget"),
    ICEBREAKER("Icebreaker", "minecraft:iron_pickaxe"),
    RANDOM(FloorDropLog.RANDOM, "minecraft:paper");

    public final String label;
    public final String itemId;

    FloorDropType(String label, String itemId) {
        this.label = label;
        this.itemId = itemId;
    }

    public static FloorDropType of(String predicted) {
        for (FloorDropType type : values()) {
            if (type.label.equals(predicted)) {
                return type;
            }
        }
        return RANDOM;
    }

    public boolean shown(ShinyConfig c) {
        return switch (this) {
            case LIME_GEM -> c.fdLimeShow;
            case PURPLE_GEM -> c.fdPurpleShow;
            case ORANGE_GEM -> c.fdOrangeShow;
            case WRIGGLEWORM -> c.fdWormShow;
            case YOGI_BERRY -> c.fdBerryShow;
            case BAG_OF_SEEDS -> c.fdSeedsShow;
            case SOOTHING_INCENSE -> c.fdIncenseShow;
            case SHINING_COIN -> c.fdCoinShow;
            case ICEBREAKER -> c.fdIcebreakerShow;
            case RANDOM -> c.fdRandomShow;
        };
    }

    public int colour(ShinyConfig c) {
        String hex = switch (this) {
            case LIME_GEM -> c.fdLimeColour;
            case PURPLE_GEM -> c.fdPurpleColour;
            case ORANGE_GEM -> c.fdOrangeColour;
            case WRIGGLEWORM -> c.fdWormColour;
            case YOGI_BERRY -> c.fdBerryColour;
            case BAG_OF_SEEDS -> c.fdSeedsColour;
            case SOOTHING_INCENSE -> c.fdIncenseColour;
            case SHINING_COIN -> c.fdCoinColour;
            case ICEBREAKER -> c.fdIcebreakerColour;
            case RANDOM -> c.fdRandomColour;
        };
        return BeeNestHighlighter.parseColor(hex, 0xFFFFFFFF);
    }

    /** Hide the rest once this many were picked up this run; 0 never hides. */
    public int hideAfter(ShinyConfig c) {
        return switch (this) {
            case LIME_GEM -> c.fdLimeHideAfter;
            case PURPLE_GEM -> c.fdPurpleHideAfter;
            case ORANGE_GEM -> c.fdOrangeHideAfter;
            case WRIGGLEWORM -> c.fdWormHideAfter;
            case YOGI_BERRY -> c.fdBerryHideAfter;
            case BAG_OF_SEEDS -> c.fdSeedsHideAfter;
            case SOOTHING_INCENSE -> c.fdIncenseHideAfter;
            case SHINING_COIN -> c.fdCoinHideAfter;
            case ICEBREAKER -> c.fdIcebreakerHideAfter;
            case RANDOM -> 0;
        };
    }
}
