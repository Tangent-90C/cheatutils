package com.zergatul.cheatutils.compatibility;

import com.zergatul.mixin.MixinPlugin;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FabricMixinPlugin extends MixinPlugin {

    /**
     * Per CSMC version: the class a mixin needs and a string that only that class carries. The class name
     * alone is no proof - 6.0 reused {@code b$2j} for an unrelated resource loader, so a name-only check
     * passes while every injection point is gone, which is a mixin apply failure instead of a skip.
     */
    private static final Map<String, String[]> CSMC_CLASS_GATES = Map.of(
            // 6.0: shot direction, which applies the recoil punch through b$5pg
            "MixinCsmcSpread", new String[] {"me/fadeorite/csmcmod/b$5os.class", "me/fadeorite/csmcmod/b$5pg"},
            // 5.14 and earlier: shot direction, whose spread magnitude comes from W
            "MixinCsmcSpreadLegacy", new String[] {"me/fadeorite/csmcmod/b$2j.class", "me/fadeorite/csmcmod/W"});

    private final Logger logger = LogManager.getLogger(FabricMixinPlugin.class);

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("com.zergatul.cheatutils.mixins.fabric.compatibility.sodium.")) {
            List<ModContainerImpl> mods = FabricLoaderImpl.INSTANCE.getModsInternal();
            boolean sodium = mods.stream().anyMatch(m -> m.getMetadata().getId().equals("sodium"));
            if (sodium) {
                logger.info("Sodium detected. Will apply {}.", mixinClassName);
                return true;
            } else {
                return false;
            }
        }

        if (mixinClassName.startsWith("com.zergatul.cheatutils.mixins.fabric.compatibility.iris.")) {
            List<ModContainerImpl> mods = FabricLoaderImpl.INSTANCE.getModsInternal();
            boolean iris = mods.stream().anyMatch(m -> m.getMetadata().getId().equals("iris"));
            if (iris) {
                logger.info("Iris detected. Will apply {}.", mixinClassName);
                return true;
            } else {
                return false;
            }
        }

        if (mixinClassName.startsWith("com.zergatul.cheatutils.mixins.fabric.compatibility.csmc.")) {
            String[] gate = CSMC_CLASS_GATES.get(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
            if (gate != null && hasCsmcClass(gate[0], gate[1])) {
                logger.info("CSMCMod detected. Will apply {}.", mixinClassName);
                return true;
            } else {
                return false;
            }
        }

        return super.shouldApplyMixin(targetClassName, mixinClassName);
    }

    /**
     * CSMCMod classes are obfuscated and renamed between versions. Reading the class file avoids loading
     * it and keeps a CSMC update from producing mixin apply failures for the rest of the mod: if the class
     * is gone the mixin is simply never applied.
     * <p>
     * A mod's root paths are its content roots: a zip filesystem over the jar for jar mods (a directory, not
     * the jar file), a plain directory for folder mods - so the entry is resolved against the root rather
     * than opening a jar.
     * <p>
     * Presence of the class is not enough by itself. 6.0 kept the name {@code b$2j} for an unrelated
     * resource loader, so a name-only check there passes while the shot-direction code lives under another
     * name, and the mixin then fails to apply to the wrong class. The class must therefore also reference the
     * helper that version hooks - {@code b$5pg} in 6.0's shot direction, {@code W} in 5.14's, {@code b$5qz}
     * for the fire handler - a string only the real class carries. If the mod is installed but no known
     * version is found, say so once per missing entry: a silent "false" here is indistinguishable from the
     * hooks never having existed.
     */
    private boolean hasCsmcClass(String entry, String marker) {
        boolean csmcInstalled = false;
        for (ModContainerImpl mod : FabricLoaderImpl.INSTANCE.getModsInternal()) {
            if (!mod.getMetadata().getId().equals("csmcmod")) {
                continue;
            }
            csmcInstalled = true;
            for (Path root : mod.getRootPaths()) {
                try {
                    if (Files.isRegularFile(root)) {
                        try (ZipFile jar = new ZipFile(root.toFile())) {
                            ZipEntry jarEntry = jar.getEntry(entry);
                            if (jarEntry == null) {
                                continue;
                            }
                            try (InputStream in = jar.getInputStream(jarEntry)) {
                                if (contains(in.readAllBytes(), marker)) {
                                    return true;
                                }
                            }
                        }
                    } else {
                        Path classFile = root.resolve(entry);
                        if (Files.isRegularFile(classFile) && contains(Files.readAllBytes(classFile), marker)) {
                            return true;
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    logger.warn("Cannot inspect CSMCMod root {}", root, e);
                }
            }
        }
        if (csmcInstalled) {
            logger.warn("CSMCMod is installed but {} (referencing {}) was not found in it. "
                    + "The matching CSMC options stay disabled.", entry, marker);
        }
        return false;
    }

    /** Plain byte search: the class file constant pool holds the name we look for verbatim. */
    private static boolean contains(byte[] content, String marker) {
        byte[] needle = marker.getBytes(StandardCharsets.UTF_8);
        if (needle.length == 0 || content.length < needle.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= content.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (content[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}