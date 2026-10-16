package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import javafx.scene.control.SpinnerValueFactory;

/** Drehungen im Kreis von 0 bis unter 360 Grad: Umrechnen getippter Werte und Pfeiltasten. */
class RotationInputTest {

	private static final double DELTA = 1e-9;

	@Test
	void normalizeBringsEveryAngleOntoTheCircle() {
		assertEquals(0, RotationInput.normalize(0), DELTA);
		assertEquals(0, RotationInput.normalize(360), DELTA);
		assertEquals(40, RotationInput.normalize(400), DELTA);
		assertEquals(90, RotationInput.normalize(450), DELTA);
		assertEquals(350, RotationInput.normalize(-10), DELTA);
		assertEquals(270, RotationInput.normalize(-90), DELTA);
		assertEquals(359.5, RotationInput.normalize(359.5), DELTA);
	}

	@Test
	void normalizeNeverReturnsMinusZeroOr360() {
		assertEquals(Double.doubleToLongBits(0.0), Double.doubleToLongBits(RotationInput.normalize(-360)));
		assertEquals(Double.doubleToLongBits(0.0), Double.doubleToLongBits(RotationInput.normalize(-0.0)));
		// -1e-14 + 360 ergibt in double genau 360
		assertEquals(0, RotationInput.normalize(-1e-14), DELTA);
	}

	@Test
	void turnWrapsAroundInBothDirections() {
		assertEquals(345, RotationInput.turn(0.0, -1, 15), DELTA);
		assertEquals(0, RotationInput.turn(345.0, 1, 15), DELTA);
		assertEquals(270, RotationInput.turn(0.0, -1, 90), DELTA);
		assertEquals(0, RotationInput.turn(270.0, 1, 90), DELTA);
		assertEquals(30, RotationInput.turn(0.0, 2, 15), DELTA);
		assertEquals(15, RotationInput.turn(null, 1, 15), DELTA);
	}

	@Test
	void turnRoundsAwayCalculationResidue() {
		double value = 0;
		for (int i = 0; i < 7; i++) {
			value = RotationInput.turn(value, 1, 0.1);
		}
		assertEquals(0.7, value, 0);
	}

	@Test
	void factoryStepsAroundTheCircle() {
		SpinnerValueFactory<Double> factory = RotationInput.factory(15);
		assertEquals(0.0, factory.getValue(), DELTA);

		factory.decrement(1);
		assertEquals(345.0, factory.getValue(), DELTA);
		factory.increment(2);
		assertEquals(15.0, factory.getValue(), DELTA);
	}

	@Test
	void factoryNormalizesSetValues() {
		SpinnerValueFactory<Double> factory = RotationInput.factory(15);

		factory.setValue(360.0);
		assertEquals(0.0, factory.getValue(), DELTA);
		factory.setValue(-10.0);
		assertEquals(350.0, factory.getValue(), DELTA);
		factory.setValue(400.0);
		assertEquals(40.0, factory.getValue(), DELTA);
	}

	@Test
	void factoryIsADoubleFactoryForSpinnerInput() {
		// SpinnerInput.install verlangt eine DoubleSpinnerValueFactory
		assertEquals(true, RotationInput.factory(90) instanceof SpinnerValueFactory.DoubleSpinnerValueFactory);
		assertEquals(90, ((SpinnerValueFactory.DoubleSpinnerValueFactory) RotationInput.factory(90)).getAmountToStepBy(), DELTA);
	}
}
