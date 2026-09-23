/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package com.zergatul.cheatutils.ballistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class BallisticProfileStoreTest
{
	@Test
	void profileRoundTripsThroughJson(@TempDir Path temporaryDirectory)
		throws IOException
	{
		WeaponFingerprint fingerprint =
			new WeaponFingerprint("server", "minecraft:overworld",
				"tacz:modern_kinetic_gun", "tacz:m1911", "SEMI", "abcdef");
		WeaponFingerprint secondFingerprint =
			new WeaponFingerprint("server", "minecraft:overworld",
				"tacz:modern_kinetic_gun", "cib:hk433", "AUTO", "123456");
		BallisticProfile profile = new BallisticProfile(fingerprint);
		profile.addShot(11.49, 0.965, 0.105);
		profile.addShot(11.51, 0.965, 0.105);
		profile.addShot(11.53, 0.965, 0.105);
		BallisticProfile secondProfile =
			new BallisticProfile(secondFingerprint);
		secondProfile.addShot(24.95, 0.9875, 0.0686);
		secondProfile.addShot(25, 0.9875, 0.0686);
		secondProfile.addShot(25.05, 0.9875, 0.0686);
		
		BallisticProfileStore store = new BallisticProfileStore(
			temporaryDirectory.resolve("ballistic-profiles.json"));
		store
			.save(Map.of(fingerprint, profile, secondFingerprint, secondProfile)
				.values());
		Map<WeaponFingerprint, BallisticProfile> loaded = store.load();
		
		assertEquals(2, loaded.size());
		BallisticProfile restored = loaded.get(fingerprint);
		assertTrue(restored.isSpeedUsable());
		assertTrue(restored.hasTrajectoryModel());
		assertEquals(11.51, restored.getMuzzleSpeed());
		assertEquals(0.965, restored.getDrag());
		assertEquals(0.105, restored.getGravity());
		assertEquals(3, restored.getAcceptedShotCount());
		BallisticProfile secondRestored = loaded.get(secondFingerprint);
		assertTrue(secondRestored.hasTrajectoryModel());
		assertEquals(25, secondRestored.getMuzzleSpeed());
		assertEquals(0.9875, secondRestored.getDrag());
		assertEquals(0.0686, secondRestored.getGravity());
	}
}
