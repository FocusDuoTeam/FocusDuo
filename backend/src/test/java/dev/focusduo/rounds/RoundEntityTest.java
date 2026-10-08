package dev.focusduo.rounds;

import dev.focusduo.api.Api;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class RoundEntityTest {
    private static final Instant START = Instant.parse("2026-10-08T16:00:00Z");

    @Test
    void pausedTimeIsExcludedAndResumeKeepsOriginalStart() {
        RoundEntity round = focus(1500);
        round.pause(START.plusSeconds(378));
        Api.Round paused = round.snapshot(START.plusSeconds(1000));
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.endsAt()).isNull();
        assertThat(paused.remainingMs()).isEqualTo(1_122_000);
        assertThat(paused.activeElapsedMs()).isEqualTo(378_000);

        round.resume(START.plusSeconds(398));
        Api.Round resumed = round.snapshot(START.plusSeconds(400));
        assertThat(resumed.startedAt()).isEqualTo(START);
        assertThat(resumed.endsAt()).isEqualTo(START.plusSeconds(1520));
        assertThat(resumed.activeElapsedMs()).isEqualTo(380_000);
        assertThat(resumed.remainingMs()).isEqualTo(1_120_000);
    }

    @Test
    void overdueReconciliationEndsAtDeadlineEvenWhenHandledMuchLater() {
        RoundEntity round = focus(300);
        assertThat(round.due(START.plusMillis(299_999))).isFalse();
        assertThat(round.due(START.plusSeconds(300))).isTrue();
        assertThat(round.due(START.plusSeconds(6000))).isTrue();
        round.completeElapsed();
        Api.Round complete = round.snapshot(START.plusSeconds(6000));
        assertThat(complete.status()).isEqualTo("COMPLETED");
        assertThat(complete.completionReason()).isEqualTo("ELAPSED");
        assertThat(complete.endedAt()).isEqualTo(START.plusSeconds(300));
        assertThat(complete.activeElapsedMs()).isEqualTo(300_000);
        assertThat(complete.remainingMs()).isZero();
        assertThat(complete.endsAt()).isNull();
        assertThat(round.due(START.plusSeconds(6000))).isFalse();
    }

    @Test
    void earlyFinishPreservesActualElapsedInsteadOfDerivingItFromZeroRemaining() {
        RoundEntity round = focus(300);
        round.pause(START.plusMillis(12_345));
        round.resume(START.plusSeconds(100));
        round.finish(START.plusMillis(123_456));
        Api.Round complete = round.snapshot(START.plusSeconds(800));
        assertThat(complete.status()).isEqualTo("COMPLETED");
        assertThat(complete.completionReason()).isEqualTo("MANUAL");
        assertThat(complete.activeElapsedMs()).isEqualTo(35_801);
        assertThat(complete.remainingMs()).isZero();
        assertThat(complete.endedAt()).isEqualTo(START.plusMillis(123_456));
    }

    @Test
    void finishingPausedRoundDoesNotCountPause() {
        RoundEntity round = focus(300);
        round.pause(START.plusSeconds(10));
        round.finish(START.plusSeconds(2000));
        assertThat(round.snapshot(START.plusSeconds(3000)).activeElapsedMs()).isEqualTo(10_000);
    }

    @Test
    void cancellationIsTerminalAndCannotBeOverwritten() {
        RoundEntity round = focus(300);
        round.pause(START.plusSeconds(25));
        round.cancel(START.plusSeconds(100));
        Api.Round cancelled = round.snapshot(START.plusSeconds(600));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.completionReason()).isEqualTo("ROOM_CLOSED");
        assertThat(cancelled.activeElapsedMs()).isEqualTo(25_000);
        assertThat(cancelled.endedAt()).isEqualTo(START.plusSeconds(100));
        assertThat(cancelled.remainingMs()).isZero();
        assertThatIllegalStateException().isThrownBy(() -> round.finish(START.plusSeconds(200)));
    }

    @Test
    void repeatedPauseCyclesCountOnlyRunningSegments() {
        RoundEntity round = focus(300);
        round.pause(START.plusSeconds(10));
        round.resume(START.plusSeconds(20));
        round.pause(START.plusSeconds(40));
        round.resume(START.plusSeconds(90));
        assertThat(round.snapshot(START.plusSeconds(100)).activeElapsedMs()).isEqualTo(40_000);
        assertThat(round.snapshot(START.plusSeconds(100)).endsAt()).isEqualTo(START.plusSeconds(360));
        round.completeElapsed();
        assertThat(round.snapshot(START.plusSeconds(400)).endedAt()).isEqualTo(START.plusSeconds(360));
    }

    @Test
    void clockEarlierThanSegmentDoesNotProduceNegativeElapsed() {
        RoundEntity round = focus(300);
        assertThat(round.snapshot(START.minusSeconds(2)).activeElapsedMs()).isZero();
        assertThat(round.snapshot(START.minusSeconds(2)).remainingMs()).isEqualTo(300_000);
    }

    @Test
    void invalidTransitionsFailWithoutChangingState() {
        RoundEntity round = focus(300);
        assertThatIllegalStateException().isThrownBy(() -> round.resume(START));
        round.pause(START.plusSeconds(1));
        assertThatIllegalStateException().isThrownBy(() -> round.pause(START.plusSeconds(2)));
        assertThat(round.snapshot(START.plusSeconds(3)).status()).isEqualTo("PAUSED");
    }

    private static RoundEntity focus(int duration) {
        return new RoundEntity(UUID.randomUUID(), "FOCUS", duration, START);
    }
}
