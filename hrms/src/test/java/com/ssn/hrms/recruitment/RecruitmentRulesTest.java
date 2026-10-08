package com.ssn.hrms.recruitment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecruitmentRulesTest {

    @Test
    void guessesDepartmentFromTitle() {
        assertThat(RecruitmentService.guessDepartment("Senior Java Developer")).isEqualTo("Engineering");
        assertThat(RecruitmentService.guessDepartment("Financial Analyst")).isEqualTo("Finance");
        assertThat(RecruitmentService.guessDepartment("UX Designer")).isEqualTo("Design");
        assertThat(RecruitmentService.guessDepartment("Talent Acquisition Specialist")).isEqualTo("Human Resources");
        assertThat(RecruitmentService.guessDepartment("Warehouse Lead")).isEqualTo("Operations");
    }
}
