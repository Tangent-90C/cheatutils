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

import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

public final class BallisticSolverTest
{
	@Test
	void closedFormMatchesDiscreteUpdateOrder()
	{
		double drag = 0.9875;
		double gravity = 0.0686;
		double initialY = 6.0001605038;
		double positionY = 0;
		double velocityY = initialY;
		for(int tick = 0; tick < 20; tick++)
		{
			assertEquals(
				initialY * BallisticSolver.dragSum(drag, tick)
					- BallisticSolver.gravityDrop(drag, gravity, tick),
				positionY, 1e-9);
			positionY += velocityY;
			velocityY = drag * velocityY - gravity;
		}
	}
	
	@Test
	void projectilePositionMatchesThreeDimensionalDiscreteSimulation()
	{
		Vec3 start = new Vec3(3, 70, -5);
		Vec3 initialVelocity = new Vec3(11, 2, -4);
		Vec3 position = start;
		Vec3 velocity = initialVelocity;
		double drag = 0.965;
		double gravity = 0.105;
		for(int tick = 0; tick < 20; tick++)
		{
			Vec3 predicted = BallisticSolver.projectilePosition(start,
				initialVelocity, drag, gravity, tick);
			assertEquals(position.x, predicted.x, 1e-9);
			assertEquals(position.y, predicted.y, 1e-9);
			assertEquals(position.z, predicted.z, 1e-9);
			position = position.add(velocity);
			velocity = velocity.scale(drag).add(0, -gravity, 0);
		}
	}
	
	@Test
	void noDragSolverMatchesQuadraticIntercept()
	{
		BallisticSolver.Input input =
			new BallisticSolver.Input(Vec3.ZERO, new Vec3(100, 0, 0),
				new Vec3(0, 0, 0.2), Vec3.ZERO, 0, 0, 10, 1, 0, 30, 0.1);
		Optional<BallisticSolver.Solution> solution =
			BallisticSolver.solve(input);
		
		assertTrue(solution.isPresent());
		assertEquals(Math.sqrt(10000 / 99.96), solution.get().flightTicks(),
			1e-6);
		assertTrue(solution.get().leadTotal().z > 2);
	}
	
	@Test
	void hk433SolutionIncludesDragAndGravityCompensation()
	{
		BallisticSolver.Input input = new BallisticSolver.Input(Vec3.ZERO,
			new Vec3(200, 0, 0), new Vec3(0, 0, 0.1), Vec3.ZERO, 0, 0, 25,
			0.9875, 0.0686, 100, 0.75);
		Optional<BallisticSolver.Solution> solution =
			BallisticSolver.solve(input);
		
		assertTrue(solution.isPresent());
		assertTrue(solution.get().flightTicks() > 8);
		assertTrue(solution.get().leadGravity().y > 0);
		assertTrue(solution.get().verificationMiss() <= 0.75);
	}
	
	@Test
	void dragLimitedWeaponReturnsNoSolutionBeyondMaximumRange()
	{
		BallisticSolver.Input input =
			new BallisticSolver.Input(Vec3.ZERO, new Vec3(500, 0, 0),
				Vec3.ZERO, Vec3.ZERO, 0, 0, 11.51, 0.965, 0.105, 200, 0.75);
		
		assertTrue(BallisticSolver.solve(input).isEmpty());
	}
	
	@Test
	void lowArcIsFasterThanHighArcWhenBothExist()
	{
		BallisticSolver.Input input =
			new BallisticSolver.Input(Vec3.ZERO, new Vec3(50, 0, 0),
				Vec3.ZERO, Vec3.ZERO, 0, 0, 5.5, 1, 0.19, 600, 0.75);
		Optional<BallisticSolver.Solution> low =
			BallisticSolver.solve(input, BallisticSolver.Arc.LOW);
		Optional<BallisticSolver.Solution> high =
			BallisticSolver.solve(input, BallisticSolver.Arc.HIGH);
		
		assertTrue(low.isPresent());
		assertTrue(high.isPresent());
		assertTrue(low.get().flightTicks() < high.get().flightTicks());
		assertTrue(low.get().pitch() > high.get().pitch());
	}
	
	@Test
	void limitlessVehicleCannonInheritsVehicleVelocity()
	{
		Vec3 vehicleVelocity = new Vec3(0.4, 0, 0);
		BallisticSolver.Input input = new BallisticSolver.Input(Vec3.ZERO,
			new Vec3(0, 0, 200), Vec3.ZERO, vehicleVelocity, 0, 1, 20, 0.99,
			0.024500001, 200, 1);
		Optional<BallisticSolver.Solution> solution =
			BallisticSolver.solve(input, BallisticSolver.Arc.LOW);
		
		assertTrue(solution.isPresent());
		assertTrue(solution.get().direction().x < 0);
		assertTrue(solution.get().leadShooter().x < 0);
		assertTrue(solution.get().verificationMiss() <= 1);
	}
}
