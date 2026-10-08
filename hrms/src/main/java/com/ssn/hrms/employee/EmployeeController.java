package com.ssn.hrms.employee;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.config.NodeOnly;

@NodeOnly
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeService employees;

    public EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @PostMapping
    public Employee register(@RequestAttribute("user") CurrentUser user, @RequestBody EmployeeService.RegisterRequest request) {
        return employees.register(user, request);
    }

    @GetMapping("/departments")
    public List<String> departments() {
        return EmployeeService.DEPARTMENTS;
    }

    @GetMapping("/suggest")
    public List<SearchIndex.EmployeeHit> suggest(@RequestParam(defaultValue = "") String q) {
        return employees.suggest(q);
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "50") int limit) {
        return employees.search(q, department, limit);
    }

    @GetMapping("/{id}")
    public Employee get(@RequestAttribute("user") CurrentUser user, @PathVariable String id) {
        return employees.view(user, id);
    }

    @PutMapping("/{id}")
    public Employee update(@RequestAttribute("user") CurrentUser user, @PathVariable String id,
            @RequestBody EmployeeService.UpdateRequest request) {
        return employees.update(user, id, request);
    }

    @GetMapping("/{id}/team")
    public List<Employee> team(@PathVariable String id) {
        return employees.team(id);
    }
}
