package com.ssn.hrms.recruitment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CandidateTest {

    @Test
    void pipelineTransitions() {
        assertThat(Candidate.canMove("APPLIED", "SCREENING")).isTrue();
        assertThat(Candidate.canMove("APPLIED", "OFFER")).isFalse();
        assertThat(Candidate.canMove("INTERVIEW", "REJECTED")).isTrue();
        assertThat(Candidate.canMove("OFFER", "HIRED")).isTrue();
        assertThat(Candidate.canMove("HIRED", "REJECTED")).isFalse();
        assertThat(Candidate.canMove("REJECTED", "SCREENING")).isFalse();
    }
}
