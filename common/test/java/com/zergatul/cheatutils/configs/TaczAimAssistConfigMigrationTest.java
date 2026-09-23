package com.zergatul.cheatutils.configs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TaczAimAssistConfigMigrationTest {

    @Test
    public void convertsStringEntriesToObjects() {
        JsonObject root = new JsonObject();
        JsonObject config = new JsonObject();
        JsonArray entries = new JsonArray();
        entries.add("minecraft:player");
        entries.add("minecraft:villager");
        config.add("targetEntities", entries);
        JsonArray blocks = new JsonArray();
        blocks.add("minecraft:oak_leaves");
        config.add("penetrableBlocks", blocks);
        root.add("taczAimAssist", config);

        ConfigStore.migrateConfigTree(root);

        JsonArray migrated = root.getAsJsonObject("taczAimAssist")
                .getAsJsonArray("targetEntities");
        assertEquals(2, migrated.size());
        JsonObject first = migrated.get(0).getAsJsonObject();
        assertEquals("minecraft:player", first.get("id").getAsString());
        assertTrue(first.get("enabled").getAsBoolean());
        JsonObject second = migrated.get(1).getAsJsonObject();
        assertEquals("minecraft:villager", second.get("id").getAsString());
        assertTrue(second.get("enabled").getAsBoolean());

        JsonArray migratedBlocks = root.getAsJsonObject("taczAimAssist")
                .getAsJsonArray("penetrableBlocks");
        assertEquals(1, migratedBlocks.size());
        JsonObject block = migratedBlocks.get(0).getAsJsonObject();
        assertEquals("minecraft:oak_leaves", block.get("block").getAsString());
        assertTrue(block.get("enabled").getAsBoolean());
    }

    @Test
    public void keepsObjectEntriesUnchanged() {
        JsonObject root = new JsonObject();
        JsonObject config = new JsonObject();
        JsonArray entries = new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("id", "group:monster");
        entry.addProperty("enabled", false);
        entries.add(entry);
        config.add("targetEntities", entries);
        root.add("taczAimAssist", config);

        ConfigStore.migrateConfigTree(root);

        JsonArray migrated = root.getAsJsonObject("taczAimAssist")
                .getAsJsonArray("targetEntities");
        assertEquals(1, migrated.size());
        JsonObject first = migrated.get(0).getAsJsonObject();
        assertEquals("group:monster", first.get("id").getAsString());
        assertFalse(first.get("enabled").getAsBoolean());
    }

    @Test
    public void ignoresMissingOrInvalidConfig() {
        JsonObject empty = new JsonObject();
        ConfigStore.migrateConfigTree(empty);
        assertNull(empty.get("taczAimAssist"));

        JsonObject root = new JsonObject();
        root.add("taczAimAssist", new JsonObject());
        ConfigStore.migrateConfigTree(root);
        assertFalse(root.getAsJsonObject("taczAimAssist").has("targetEntities"));

        JsonElement notAnObject = JsonParser.parseString("\"text\"");
        ConfigStore.migrateConfigTree(notAnObject);
    }
}
