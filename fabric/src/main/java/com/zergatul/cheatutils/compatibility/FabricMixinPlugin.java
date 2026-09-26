package com.zergatul.cheatutils.compatibility;

import com.zergatul.mixin.MixinPlugin;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

public class FabricMixinPlugin extends MixinPlugin {

    private static final String CSMC_DIRECTION_CLASS = "me/fadeorite/csmcmod/b$2j.class";

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
            if (hasCsmcDirectionClass()) {
                logger.info("CSMCMod detected. Will apply {}.", mixinClassName);
                return true;
            } else {
                return false;
            }
        }

        return super.shouldApplyMixin(targetClassName, mixinClassName);
    }

    /**
     * CSMCMod classes are obfuscated and renamed between versions. Checking for the class file avoids loading
     * it and keeps a CSMC update from producing mixin apply failures for the rest of the mod: if the class is
     * gone the mixin is simply never applied.
     * <p>
     * A mod's root paths are its content roots: a zip filesystem over the jar for jar mods (a directory, not
     * the jar file), a plain directory for folder mods - so the entry is resolved against the root rather than
     * opening a jar. If the mod is installed but the class is gone, say so once: a silent "false" here is
     * indistinguishable from the hooks never having existed.
     */
    private boolean hasCsmcDirectionClass() {
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
                            if (jar.getEntry(CSMC_DIRECTION_CLASS) != null) {
                                return true;
                            }
                        }
                    } else if (Files.isRegularFile(root.resolve(CSMC_DIRECTION_CLASS))) {
                        return true;
                    }
                } catch (IOException | RuntimeException e) {
                    logger.warn("Cannot inspect CSMCMod root {}", root, e);
                }
            }
        }
        if (csmcInstalled) {
            logger.warn("CSMCMod is installed but {} was not found in it. CSMC spread/recoil options stay disabled.",
                    CSMC_DIRECTION_CLASS);
        }
        return false;
    }
}