package com.openenv.tickettriage;

import com.openenv.tickettriage.grader.EasyTaskGrader;
import com.openenv.tickettriage.grader.HardTaskGrader;
import com.openenv.tickettriage.grader.MediumTaskGrader;
import com.openenv.tickettriage.model.Action;
import com.openenv.tickettriage.model.Reward;
import com.openenv.tickettriage.model.SupportTicket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GraderUnitTest {

    // Sample ticket: CRITICAL / SECURITY / SECURITY_OPS
    SupportTicket criticalSecurityTicket;
    // Sample ticket: MEDIUM / SOFTWARE / HELPDESK
    SupportTicket mediumSoftwareTicket;

    @BeforeEach
    void setup() {
        criticalSecurityTicket = SupportTicket.builder()
                .ticketId("TKT-T1")
                .title("Phishing attack — credentials entered")
                .description("Employee clicked phishing link and entered credentials.")
                .groundTruthPriority(SupportTicket.Priority.CRITICAL)
                .groundTruthCategory(SupportTicket.Category.SECURITY)
                .groundTruthTeam(SupportTicket.Team.SECURITY_OPS)
                .groundTruthResolutionHint("Reset credentials, revoke sessions, audit logs, notify CISO.")
                .keywords(List.of("phishing","credentials","security"))
                .build();

        mediumSoftwareTicket = SupportTicket.builder()
                .ticketId("TKT-T2")
                .title("Teams calls dropping")
                .description("Microsoft Teams calls disconnect after 2 minutes.")
                .groundTruthPriority(SupportTicket.Priority.MEDIUM)
                .groundTruthCategory(SupportTicket.Category.SOFTWARE)
                .groundTruthTeam(SupportTicket.Team.HELPDESK)
                .groundTruthResolutionHint("Clear Teams cache, update client, check QoS policy.")
                .keywords(List.of("teams","dropping","disconnect"))
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // EASY Grader
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("EasyTaskGrader")
    class EasyGraderTests {
        EasyTaskGrader grader = new EasyTaskGrader();

        @Test
        @DisplayName("Exact match → reward = 1.0")
        void exactMatch() {
            Action action = Action.builder().priority("CRITICAL").build();
            Reward reward = grader.grade(action, criticalSecurityTicket, 0.0);
            assertThat(reward.getValue()).isCloseTo(1.0, within(0.001));
            assertThat(reward.getPenaltyApplied()).isEqualTo(0.0);
            assertThat(reward.isEpisodeComplete()).isTrue();
        }

        @Test
        @DisplayName("One level off (HIGH vs CRITICAL) → reward = 0.5")
        void oneLevelOff() {
            Action action = Action.builder().priority("HIGH").build();
            Reward reward = grader.grade(action, criticalSecurityTicket, 0.0);
            assertThat(reward.getValue()).isCloseTo(0.5, within(0.001));
        }

        @Test
        @DisplayName("Two levels off (MEDIUM vs CRITICAL) → reward = 0.2")
        void twoLevelsOff() {
            Action action = Action.builder().priority("MEDIUM").build();
            Reward reward = grader.grade(action, criticalSecurityTicket, 0.0);
            assertThat(reward.getValue()).isCloseTo(0.2, within(0.001));
        }

        @Test
        @DisplayName("Three levels off (LOW vs CRITICAL) → reward = 0.0")
        void threeLevelsOff() {
            Action action = Action.builder().priority("LOW").build();
            Reward reward = grader.grade(action, criticalSecurityTicket, 0.0);
            assertThat(reward.getValue()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Invalid priority value → penalty 0.1, reward = 0.0")
        void invalidPriority() {
            Action action = Action.builder().priority("EXTREME").build();
            Reward reward = grader.grade(action, criticalSecurityTicket, 0.0);
            assertThat(reward.getValue()).isEqualTo(0.0);
            assertThat(reward.getPenaltyApplied()).isCloseTo(0.1, within(0.001));
        }

        @Test
        @DisplayName("Case-insensitive: 'critical' → reward = 1.0")
        void caseInsensitive() {
            Action action = Action.builder().priority("critical").build();
            Reward reward = grader.grade(action, criticalSecurityTicket, 0.0);
            assertThat(reward.getValue()).isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("Null priority → penalty applied")
        void nullPriority() {
            Action action = Action.builder().build();
            Reward reward = grader.grade(action, criticalSecurityTicket, 0.0);
            assertThat(reward.getValue()).isEqualTo(0.0);
            assertThat(reward.getPenaltyApplied()).isGreaterThan(0.0);
        }
    }

    // ─────────────────────────────────────────────────────────
    // MEDIUM Grader
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("MediumTaskGrader")
    class MediumGraderTests {
        MediumTaskGrader grader = new MediumTaskGrader();

        @Test
        @DisplayName("Perfect action → reward = 1.0")
        void perfectAction() {
            Action action = Action.builder()
                    .priority("CRITICAL").category("SECURITY").assignedTeam("SECURITY_OPS").build();
            Reward reward = grader.grade(action, criticalSecurityTicket, 0.0);
            assertThat(reward.getValue()).isCloseTo(1.0, within(0.01));
            assertThat(reward.getComponentScores().get("priority_score")).isCloseTo(0.40, within(0.01));
            assertThat(reward.getComponentScores().get("category_score")).isCloseTo(0.35, within(0.01));
            assertThat(reward.getComponentScores().get("team_score")).isCloseTo(0.25, within(0.01));
        }

        @Test
        @DisplayName("Wrong category only → reward = 0.65 (priority + team correct)")
        void wrongCategoryOnly() {
            Action action = Action.builder()
                    .priority("CRITICAL").category("HARDWARE").assignedTeam("SECURITY_OPS").build();
            Reward reward = grader.grade(action, criticalSecurityTicket, 0.0);
            // priority=0.40 + team=0.25 + category=0.00 = 0.65
            assertThat(reward.getValue()).isCloseTo(0.65, within(0.01));
        }

        @Test
        @DisplayName("Missing team → penalty applied, team_score = 0")
        void missingTeam() {
            Action action = Action.builder()
                    .priority("CRITICAL").category("SECURITY").build();
            Reward reward = grader.grade(action, criticalSecurityTicket, 0.0);
            assertThat(reward.getComponentScores().get("team_score")).isEqualTo(0.0);
            assertThat(reward.getPenaltyApplied()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("All wrong → reward near 0")
        void allWrong() {
            Action action = Action.builder()
                    .priority("LOW").category("HARDWARE").assignedTeam("HELPDESK").build();
            Reward reward = grader.grade(action, criticalSecurityTicket, 0.0);
            assertThat(reward.getValue()).isCloseTo(0.0, within(0.05));
        }

        @Test
        @DisplayName("Priority one level off → partial priority score")
        void partialPriorityScore() {
            Action action = Action.builder()
                    .priority("HIGH").category("SECURITY").assignedTeam("SECURITY_OPS").build();
            Reward reward = grader.grade(action, criticalSecurityTicket, 0.0);
            // priority partial (0.20) + cat(0.35) + team(0.25) = 0.80
            assertThat(reward.getValue()).isCloseTo(0.80, within(0.01));
        }
    }

    // ─────────────────────────────────────────────────────────
    // HARD Grader
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("HardTaskGrader")
    class HardGraderTests {
        HardTaskGrader grader = new HardTaskGrader();

        List<SupportTicket> similarTickets = List.of(
            SupportTicket.builder().ticketId("TKT-011").groundTruthCategory(SupportTicket.Category.SECURITY).build(),
            SupportTicket.builder().ticketId("TKT-012").groundTruthCategory(SupportTicket.Category.SECURITY).build()
        );

        @Test
        @DisplayName("Perfect action → reward close to 1.0")
        void perfectAction() {
            Action action = Action.builder()
                    .priority("CRITICAL")
                    .category("SECURITY")
                    .assignedTeam("SECURITY_OPS")
                    .resolutionSuggestion("Reset credentials immediately, revoke sessions, audit logs, notify CISO")
                    .similarTicketIds(List.of("TKT-011", "TKT-012"))
                    .estimatedResolutionHours(2)
                    .build();

            Reward reward = grader.grade(action, criticalSecurityTicket, similarTickets, 0.0, true);
            assertThat(reward.getValue()).isGreaterThan(0.75);
            assertThat(reward.getComponentScores()).containsKeys(
                "priority_score","category_score","team_score","resolution_score","reference_score","time_score"
            );
        }

        @Test
        @DisplayName("No resolution suggestion → resolution_score = 0, penalty applied")
        void noResolution() {
            Action action = Action.builder()
                    .priority("CRITICAL").category("SECURITY").assignedTeam("SECURITY_OPS")
                    .estimatedResolutionHours(2)
                    .similarTicketIds(List.of("TKT-011"))
                    .build();

            Reward reward = grader.grade(action, criticalSecurityTicket, similarTickets, 0.0, true);
            assertThat(reward.getComponentScores().get("resolution_score")).isEqualTo(0.0);
            assertThat(reward.getPenaltyApplied()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("Good keyword coverage in resolution → high resolution_score")
        void goodResolutionKeywords() {
            // Ground truth hint: "Reset credentials, revoke sessions, audit logs, notify CISO."
            Action action = Action.builder()
                    .priority("CRITICAL").category("SECURITY").assignedTeam("SECURITY_OPS")
                    .resolutionSuggestion("Reset all user credentials and revoke active sessions. Check audit logs for unauthorized access. Notify CISO and file incident report.")
                    .estimatedResolutionHours(2)
                    .build();

            Reward reward = grader.grade(action, criticalSecurityTicket, similarTickets, 0.0, true);
            assertThat(reward.getComponentScores().get("resolution_score")).isGreaterThan(0.10);
        }

        @Test
        @DisplayName("Correct similar ticket references → reference_score > 0")
        void correctReferences() {
            Action action = Action.builder()
                    .priority("CRITICAL").category("SECURITY").assignedTeam("SECURITY_OPS")
                    .resolutionSuggestion("Reset credentials and revoke sessions.")
                    .similarTicketIds(List.of("TKT-011", "TKT-012"))
                    .estimatedResolutionHours(2)
                    .build();

            Reward reward = grader.grade(action, criticalSecurityTicket, similarTickets, 0.0, true);
            assertThat(reward.getComponentScores().get("reference_score")).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("Time estimate in expected range → time_score = 0.05")
        void timeInRange() {
            // CRITICAL expected: 1-4 hours
            Action action = Action.builder()
                    .priority("CRITICAL").category("SECURITY").assignedTeam("SECURITY_OPS")
                    .resolutionSuggestion("Reset credentials.")
                    .estimatedResolutionHours(3) // within [1,4]
                    .build();

            Reward reward = grader.grade(action, criticalSecurityTicket, similarTickets, 0.0, true);
            assertThat(reward.getComponentScores().get("time_score")).isCloseTo(0.05, within(0.01));
        }

        @Test
        @DisplayName("Cumulative reward accumulates across steps")
        void cumulativeReward() {
            Action action = Action.builder()
                    .priority("CRITICAL").category("SECURITY").assignedTeam("SECURITY_OPS")
                    .resolutionSuggestion("Reset credentials immediately.")
                    .estimatedResolutionHours(2).build();

            Reward step1 = grader.grade(action, criticalSecurityTicket, similarTickets, 0.0, false);
            Reward step2 = grader.grade(action, criticalSecurityTicket, similarTickets, step1.getCumulativeReward(), true);

            assertThat(step2.getCumulativeReward()).isGreaterThan(step1.getCumulativeReward());
        }
    }
}
