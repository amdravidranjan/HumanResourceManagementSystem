package com.ssn.hrms.employee;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SearchIndexTermsTest {

    @Test
    void indexesFullNameSurnameDepartmentDesignationAndSkills() {
        Employee e = new Employee();
        e.id = "1";
        e.name = "Ravi Shankar Iyer";
        e.department = "Engineering";
        e.designation = "Senior Engineer";
        e.skills = List.of("Java", "Kubernetes", "java");
        assertThat(SearchIndex.employeeTerms(e)).containsExactly(
                "Ravi Shankar Iyer", "Shankar", "Iyer", "Engineering", "Senior Engineer", "Java", "Kubernetes");
    }

    @Test
    void toleratesMissingFields() {
        Employee e = new Employee();
        e.id = "2";
        e.name = "Meena";
        e.skills = null;
        assertThat(SearchIndex.employeeTerms(e)).containsExactly("Meena");
    }
}
