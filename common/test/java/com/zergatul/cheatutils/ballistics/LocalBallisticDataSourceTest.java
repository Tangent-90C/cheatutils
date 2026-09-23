/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package com.zergatul.cheatutils.ballistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.nbt.CompoundTag;

public final class LocalBallisticDataSourceTest
{
	@TempDir
	Path tempDir;
	
	@Test
	void readsTaczDirectoryAndConvertsUnits() throws Exception
	{
		Path gun =
			tempDir.resolve("tacz/Pack/data/example/data/guns/rifle_data.json");
		Files.createDirectories(gun.getParent());
		Files.writeString(gun, "{\n" + "  // comments used by real gun packs\n"
			+ "  \"bullet\": {\"speed\": 200, \"gravity\": 0.15, \"friction\": 0.02}\n"
			+ "}", StandardCharsets.UTF_8);
		
		LocalBallisticDataSource source = new LocalBallisticDataSource(tempDir);
		source.refreshNow();
		var lookup =
			source.lookup(fingerprint("example:rifle"), (CompoundTag)null);
		
		assertTrue(lookup.complete());
		assertEquals(20, lookup.muzzleSpeed(), 1e-9);
		assertEquals(0.98, lookup.drag(), 1e-9);
		assertEquals(0.15, lookup.gravity(), 1e-9);
	}
	
	@Test
	void readsTaczZipAndReportsMissingFields() throws Exception
	{
		Path tacz = tempDir.resolve("tacz");
		Files.createDirectories(tacz);
		try(ZipOutputStream zip = new ZipOutputStream(
			Files.newOutputStream(tacz.resolve("pack.zip"))))
		{
			zip.putNextEntry(
				new ZipEntry("data/example/data/guns/broken_data.json"));
			zip.write("{\"bullet\":{\"gravity\":0.1}}"
				.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		
		LocalBallisticDataSource source = new LocalBallisticDataSource(tempDir);
		source.refreshNow();
		var lookup =
			source.lookup(fingerprint("example:broken"), (CompoundTag)null);
		
		assertFalse(lookup.complete());
		assertTrue(lookup.missingFields().contains("bullet.speed"));
	}
	
	@Test
	void usesConfiguredGlobalBulletSpeedModifier() throws Exception
	{
		Path config = tempDir.resolve("config/tacz-common.toml");
		Files.createDirectories(config.getParent());
		Files.writeString(config,
			"# example tacz config\n"
				+ "[common]\n"
				+ "\tGlobalBulletSpeedModifier = 3\n"
				+ "\tOtherSetting = 1\n",
			StandardCharsets.UTF_8);
		Path gun =
			tempDir.resolve("tacz/Pack/data/example/data/guns/rifle_data.json");
		Files.createDirectories(gun.getParent());
		Files.writeString(gun,
			"{\"bullet\": {\"speed\": 200, \"gravity\": 0.15, \"friction\": 0.02}}",
			StandardCharsets.UTF_8);
		
		LocalBallisticDataSource source = new LocalBallisticDataSource(tempDir);
		source.refreshNow();
		var lookup =
			source.lookup(fingerprint("example:rifle"), (CompoundTag)null);
		
		assertTrue(lookup.complete());
		assertEquals(30, lookup.muzzleSpeed(), 1e-9);
	}
	
	@Test
	void readsGunPackFromModsJar() throws Exception
	{
		Path mods = tempDir.resolve("mods");
		Files.createDirectories(mods);
		try(ZipOutputStream zip = new ZipOutputStream(
			Files.newOutputStream(mods.resolve("tacz.jar"))))
		{
			zip.putNextEntry(new ZipEntry(
				"assets/tacz/custom/tacz_default_gun/data/tacz/data/guns/scar_l_data.json"));
			zip.write(("{\n" + "  // comments used by real gun packs\n"
				+ "  \"bullet\": {\"speed\": 290, \"gravity\": 0.15, \"friction\": 0.015}\n"
				+ "}").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("assets/tacz/icon.png"));
			zip.write(new byte[]{1, 2, 3});
			zip.closeEntry();
		}

		LocalBallisticDataSource source = new LocalBallisticDataSource(tempDir);
		source.refreshNow();
		var lookup =
			source.lookup(fingerprint("tacz:scar_l"), (CompoundTag)null);

		assertTrue(lookup.complete());
		assertEquals(29, lookup.muzzleSpeed(), 1e-9);
		assertEquals(0.985, lookup.drag(), 1e-9);
		assertEquals(0.15, lookup.gravity(), 1e-9);
		assertEquals(1, source.getStatus().gunCount());
		assertTrue(source.getStatus().state().contains("mod jars"));
	}

	private WeaponFingerprint fingerprint(String id)
	{
		return new WeaponFingerprint("server", "minecraft:overworld", id, id,
			"AUTO", "hash");
	}
}
