/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package com.zergatul.cheatutils.ballistics;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

public final class WeaponFingerprintTest
{
	@Test
	void canonicalNbtIgnoresAmmoAndKeyOrder()
	{
		CompoundTag first = gunNbt("bf1:sight_okp8", 30);
		CompoundTag second = new CompoundTag();
		second.putInt("GunCurrentAmmoCount", 5);
		second.putString("GunFireMode", "AUTO");
		CompoundTag scope = new CompoundTag();
		scope.putString("AttachmentId", "bf1:sight_okp8");
		second.put("AttachmentSCOPE", scope);
		second.putString("GunId", "cib:hk433");
		
		assertEquals(WeaponFingerprint.canonicalizeNbt(first),
			WeaponFingerprint.canonicalizeNbt(second));
	}
	
	@Test
	void attachmentChangeCreatesDifferentCanonicalData()
	{
		CompoundTag first = gunNbt("bf1:sight_okp8", 30);
		CompoundTag second = gunNbt("tacz:scope_standard_8x", 30);
		
		assertNotEquals(WeaponFingerprint.canonicalizeNbt(first),
			WeaponFingerprint.canonicalizeNbt(second));
	}
	
	@Test
	void ballisticConfigChangeAltersCanonicalData()
	{
		CompoundTag baseline = gunNbt("bf1:sight_okp8", 30);
		String baselineData = WeaponFingerprint.canonicalizeNbt(baseline);
		
		CompoundTag otherGunId = gunNbt("bf1:sight_okp8", 30);
		otherGunId.putString("GunId", "cib:m416");
		assertNotEquals(baselineData,
			WeaponFingerprint.canonicalizeNbt(otherGunId));
		
		CompoundTag otherFireMode = gunNbt("bf1:sight_okp8", 30);
		otherFireMode.putString("GunFireMode", "BURST");
		assertNotEquals(baselineData,
			WeaponFingerprint.canonicalizeNbt(otherFireMode));
		
		CompoundTag otherAmmo = gunNbt("bf1:sight_okp8", 3);
		assertEquals(baselineData,
			WeaponFingerprint.canonicalizeNbt(otherAmmo));
	}
	
	private CompoundTag gunNbt(String attachmentId, int ammo)
	{
		CompoundTag nbt = new CompoundTag();
		nbt.putString("GunId", "cib:hk433");
		nbt.putString("GunFireMode", "AUTO");
		nbt.putInt("GunCurrentAmmoCount", ammo);
		CompoundTag scope = new CompoundTag();
		scope.putString("AttachmentId", attachmentId);
		nbt.put("AttachmentSCOPE", scope);
		return nbt;
	}
}
