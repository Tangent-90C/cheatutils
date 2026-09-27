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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FabricMixinPlugin extends MixinPlugin {

    /**
     * Shot-direction class of CSMCMod 6.0 and the helper that applies the recoil punch inside it. The class
     * name alone is no proof: 6.0 reused {@code b$2j} for an unrelated resource loader, so a name check there
     * passes while every injection point is gone, which is a mixin apply failure instead of a skip.
     */
    private static final String CSMC_DIRECTION_CLASS_6 = "me/fadeorite/csmcmod/b$5os.class";
    private static final String CSMC_DIRECTION_MARKER_6 = "me/fadeorite/csmcmod/b$5pg";

    /** Same pair for CSMCMod 5.14 and earlier, where the shot direction was {@code b$2j} calling {@code W}. */
    private static final String CSMC_DIRECTION_CLASS_5 = "me/fadeorite/csmcmod/b$2j.class";
    private static final String CSMC_DIRECTION_MARKER_5 = "me/fadeorite/csmcmod/W";

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
            boolean legacy = mixinClassName.endsWith("MixinCsmcSpreadLegacy");
            String entry = legacy ? CSMC_DIRECTION_CLASS_5 : CSMC_DIRECTION_CLASS_6;
            String marker = legacy ? CSMC_DIRECTION_MARKER_5 : CSMC_DIRECTION_MARKER_6;
            if (hasCsmcShotDirection(entry, marker)) {
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
     * helper this version hooks - {@code b$5pg} in 6.0, {@code W} in 5.14 - a string only the real
     * shot-direction class carries. If the mod is installed but neither version is found, say so once: a
     * silent "false" here is indistinguishable from the hooks never having existed.
     */
    private boolean hasCsmcShotDirection(String entry, String marker) {
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
            logger.warn("CSMCMod is installed but {} was not found in it. CSMC spread/recoil options stay disabled.",
                    entry);
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