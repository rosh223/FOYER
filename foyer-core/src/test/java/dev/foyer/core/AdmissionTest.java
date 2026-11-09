package dev.foyer.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionTest {

    @Test
    void admitsTheConfiguredRateOverTime() {
        double carry = 0;
        long total = 0;
        for (int s = 0; s < 600; s++) { // ten minutes of one-second ticks at 100/min
            var step = Admission.step(100, 1000, carry, 1.0, Long.MAX_VALUE, Long.MAX_VALUE);
            total += step.admit();
            carry = step.carry();
        }
        assertThat(total).isBetween(999L, 1000L);
    }

    @Test
    void capsAndWaitingLimitAdmissions() {
        assertThat(Admission.step(6000, 1000, 0, 1, 7, 1000).admit()).isEqualTo(7);
        assertThat(Admission.step(6000, 1000, 0, 1, 1000, 3).admit()).isEqualTo(3);
        assertThat(Admission.step(6000, 1000, 0, 1, 0, 1000).admit()).isZero();
        // Being capped doesn't bank a burst for later.
        assertThat(Admission.step(6000, 1000, 0, 1, 7, 1000).carry()).isZero();
    }

    @Test
    void compensatesForNoShowsWithinABound() {
        assertThat(Admission.step(600, 1000, 0, 0.5, Long.MAX_VALUE, Long.MAX_VALUE).admit()).isEqualTo(20);
        assertThat(Admission.step(600, 1000, 0, 0.1, Long.MAX_VALUE, Long.MAX_VALUE).admit()).isEqualTo(30);
    }

    @Test
    void pollIntervalGrowsWithDistance() {
        assertThat(Admission.pollSeconds(0, 100)).isEqualTo(2);
        assertThat(Admission.pollSeconds(50, 100)).isEqualTo(3);
        assertThat(Admission.pollSeconds(300, 100)).isEqualTo(6);
        assertThat(Admission.pollSeconds(2000, 100)).isEqualTo(15);
        assertThat(Admission.pollSeconds(100_000, 100)).isEqualTo(30);
    }
}
