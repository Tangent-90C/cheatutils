/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package com.zergatul.cheatutils.ballistics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public final class BallisticCalibrationManagerTest
{
	@Test
	void supportsTaczBulletsOnly()
	{
		assertTrue(
			BallisticCalibrationManager.isSupportedProjectileId("tacz:bullet"));
		assertFalse(BallisticCalibrationManager
			.isSupportedProjectileId("minecraft:arrow"));
		assertFalse(BallisticCalibrationManager
			.isSupportedProjectileId("minecraft:snowball"));
		assertFalse(BallisticCalibrationManager
			.isSupportedProjectileId("minecraft:small_fireball"));
		assertFalse(BallisticCalibrationManager
			.isSupportedProjectileId("minecraft:firework_rocket"));
	}
	
	@Test
	void matchesProjectileTypeToWeaponMod()
	{
		WeaponFingerprint tacz = fingerprint("tacz:modern_kinetic_gun");
		WeaponFingerprint other = fingerprint("minecraft:bow");
		
		assertTrue(BallisticCalibrationManager
			.isFingerprintCompatible("tacz:bullet", tacz));
		assertFalse(BallisticCalibrationManager
			.isFingerprintCompatible("tacz:bullet", other));
	}
	
	private WeaponFingerprint fingerprint(String itemId)
	{
		return new WeaponFingerprint("server", "minecraft:overworld", itemId,
			itemId, "mode", "hash");
	}
}
