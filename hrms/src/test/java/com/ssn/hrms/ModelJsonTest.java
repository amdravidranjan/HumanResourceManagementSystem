package com.ssn.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ssn.hrms.employee.Employee;

import tools.jackson.databind.json.JsonMapper;

class ModelJsonTest {

    @Test
    void idsSerialiseAsStringsAndPasswordHashIsHidden() {
        Employee e = new Employee();
        e.id = "318472619823104001";
        e.managerId = "318472619823104999";
        e.name = "Ravi Kumar";
        e.passwordHash = "$2a$10$secret";
        String json = JsonMapper.builder().build().writeValueAsString(e);
        assertThat(json).contains("\"id\":\"318472619823104001\"").contains("\"managerId\":\"318472619823104999\"");
        assertThat(json).doesNotContain("passwordHash").doesNotContain("secret");
    }
}
