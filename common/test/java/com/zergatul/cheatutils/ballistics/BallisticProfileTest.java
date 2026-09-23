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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.zergatul.cheatutils.ballistics.BallisticProfile.Confidence;
import com.zergatul.cheatutils.ballistics.BallisticProfile.UpdateResult;

public final class BallisticProfileTest
{
	@Test
	void threeShotsMakeProfileUsable()
	{
		BallisticProfile profile = new BallisticProfile(fingerprint());
		assertFalse(profile.isSpeedUsable());
		
		profile.addShot(11.49, 0.965, 0.105);
		profile.addShot(11.53, 0.965, 0.105);
		profile.addShot(11.51, 0.965, 0.105);
		
		assertTrue(profile.isSpeedUsable());
		assertTrue(profile.hasTrajectoryModel());
		assertEquals(11.51, profile.getMuzzleSpeed());
		assertEquals(Confidence.LOW, profile.getConfidence());
	}
	
	@Test
	void isolatedOutlierDoesNotMoveStableProfile()
	{
		BallisticProfile profile = stableProfile();
		
		assertEquals(UpdateResult.REJECTED_OUTLIER,
			profile.addShot(50, 0.5, 0.5));
		assertEquals(25, profile.getMuzzleSpeed());
		assertEquals(5, profile.getAcceptedShotCount());
		assertEquals(1, profile.getRejectedShotCount());
	}
	
	@Test
	void stableNewClusterReplacesOldServerConfiguration()
	{
		BallisticProfile profile = stableProfile();
		double[] changedSpeeds = {11.49, 11.51, 11.53, 11.50, 11.52};
		UpdateResult result = null;
		for(double speed : changedSpeeds)
			result = profile.addShot(speed, 0.965, 0.105);
		
		assertEquals(UpdateResult.REPLACED, result);
		assertEquals(11.51, profile.getMuzzleSpeed());
		assertEquals(5, profile.getAcceptedShotCount());
		assertEquals(4, profile.getRejectedShotCount());
		assertEquals(Confidence.MEDIUM, profile.getConfidence());
	}
	
	@Test
	void medianAndMadAreRobust()
	{
		List<Double> values = List.of(228D, 229D, 230D, 231D, 1000D);
		assertEquals(230, BallisticProfile.median(values));
		assertEquals(1, BallisticProfile.mad(values));
	}
	
	private BallisticProfile stableProfile()
	{
		BallisticProfile profile = new BallisticProfile(fingerprint());
		for(double speed : new double[]{24.95, 24.98, 25, 25.02, 25.05})
			profile.addShot(speed, 0.9875, 0.0686);
		return profile;
	}
	
	private WeaponFingerprint fingerprint()
	{
		return new WeaponFingerprint("server", "minecraft:overworld",
			"tacz:modern_kinetic_gun", "cib:hk433", "AUTO", "hash");
	}
}
